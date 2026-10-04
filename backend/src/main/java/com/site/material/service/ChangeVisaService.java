package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.ChangeVisa;
import com.site.material.domain.Material;
import com.site.material.domain.MaterialBatch;
import com.site.material.domain.ProjectBudget;
import com.site.material.domain.Requisition;
import com.site.material.domain.RequisitionItem;
import com.site.material.dto.Requests.*;
import com.site.material.repo.ChangeVisaRepository;
import com.site.material.repo.MaterialBatchRepository;
import com.site.material.repo.MaterialRepository;
import com.site.material.repo.ProjectBudgetRepository;
import com.site.material.repo.RequisitionRepository;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 变更签证：施工/设计变更引起材料量变化与索赔，财务据此做变更索赔。
 */
@Service
public class ChangeVisaService {

    private final ChangeVisaRepository visaRepo;
    private final MaterialRepository materialRepo;
    private final RequisitionRepository reqRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final MaterialBatchRepository batchRepo;
    private final CodeGenerator codes;

    public ChangeVisaService(ChangeVisaRepository visaRepo, MaterialRepository materialRepo,
                             RequisitionRepository reqRepo, ProjectBudgetRepository budgetRepo,
                             MaterialBatchRepository batchRepo, CodeGenerator codes) {
        this.visaRepo = visaRepo;
        this.materialRepo = materialRepo;
        this.reqRepo = reqRepo;
        this.budgetRepo = budgetRepo;
        this.batchRepo = batchRepo;
        this.codes = codes;
    }

    @Transactional
    public ChangeVisa create(VisaRequest req) {
        if (req.materialId() != null) {
            materialRepo.findById(req.materialId()).orElseThrow(() -> new BusinessException("材料不存在"));
        }
        ChangeVisa v = new ChangeVisa();
        v.setCode(codes.next("QZ"));
        v.setBuilding(req.building());
        v.setArea(req.area());
        v.setProcess(req.process());
        v.setDrawingNo(req.drawingNo());
        v.setMaterialId(req.materialId());
        v.setCrewId(req.crewId());
        v.setChangeType(req.changeType());
        v.setDescription(req.description());
        v.setQtyChange(req.qtyChange() == null ? 0d : req.qtyChange());
        v.setUnitPrice(req.unitPrice() == null ? 0d : req.unitPrice());
        v.setClaimAmount(req.claimAmount() == null
                ? MasterDataService.round(v.getQtyChange() * v.getUnitPrice()) : req.claimAmount());
        v.setStatus("DRAFT");
        v.setApplicant(SecurityUtil.currentName());
        v.setCreatedTime(LocalDateTime.now());
        return visaRepo.save(v);
    }

    @Transactional
    public ChangeVisa confirm(Long id) {
        ChangeVisa v = visaRepo.findById(id).orElseThrow(() -> new BusinessException("签证不存在"));
        if (!"DRAFT".equals(v.getStatus())) {
            throw new BusinessException("仅草稿签证可确认，当前状态: " + v.getStatus());
        }
        v.setStatus("CONFIRMED");
        v.setConfirmer(SecurityUtil.currentName());
        v.setConfirmTime(LocalDateTime.now());
        return v;
    }

    @Transactional
    public ChangeVisa markClaimed(Long id) {
        ChangeVisa v = visaRepo.findById(id).orElseThrow(() -> new BusinessException("签证不存在"));
        if (!"CONFIRMED".equals(v.getStatus())) {
            throw new BusinessException("仅已确认签证可标记索赔");
        }
        v.setStatus("CLAIMED");
        return v;
    }

    public List<ChangeVisa> list() {
        return visaRepo.findAllByOrderByCreatedTimeDesc();
    }

    /**
     * 追加领料上下文：平台把设计变更关联的原预算、已领材料、现场库存与已有追加单汇总，
     * 供防水班组发起追加（卷材 + 人工）时对照"新增需求"。
     */
    public Map<String, Object> additionalContext(Long visaId) {
        ChangeVisa v = visaRepo.findById(visaId)
                .orElseThrow(() -> new BusinessException("签证不存在"));
        Map<Long, Material> mats = new LinkedHashMap<>();
        for (Material m : materialRepo.findAll()) mats.put(m.getId(), m);

        List<Map<String, Object>> budgets = new ArrayList<>();
        List<ProjectBudget> bs = v.getMaterialId() == null ? List.of()
                : budgetRepo.findByBuildingAndProcessAndMaterialId(
                        v.getBuilding(), v.getProcess(), v.getMaterialId());
        for (ProjectBudget b : bs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("materialId", b.getMaterialId());
            row.put("materialName", name(mats, b.getMaterialId()));
            row.put("unit", unit(mats, b.getMaterialId()));
            row.put("budgetQty", b.getBudgetQty());
            row.put("budgetPrice", b.getBudgetPrice());
            row.put("crewId", b.getCrewId());
            budgets.add(row);
        }

        // 已领材料（合同内口径）：该 楼栋+工序+签证涉及材料 已发料的普通领料，追加领料另列
        List<Requisition> issued = reqRepo.findAllWithItems().stream()
                .filter(r -> "ISSUED".equals(r.getStatus()))
                .filter(r -> !r.isChangeAdditional())
                .filter(r -> Objects.equals(r.getBuilding(), v.getBuilding()))
                .filter(r -> Objects.equals(r.getProcess(), v.getProcess()))
                .filter(r -> v.getMaterialId() == null || r.getItems().stream()
                        .anyMatch(i -> i.getMaterialId().equals(v.getMaterialId())))
                .toList();
        Map<Long, double[]> issuedAgg = new LinkedHashMap<>(); // materialId -> [qty, amount]
        List<String> issuedDocs = new ArrayList<>();
        for (Requisition r : issued) {
            issuedDocs.add(r.getCode());
            for (RequisitionItem i : r.getItems()) {
                if (v.getMaterialId() != null && !i.getMaterialId().equals(v.getMaterialId())) continue;
                double[] a = issuedAgg.computeIfAbsent(i.getMaterialId(), x -> new double[2]);
                a[0] += nz(i.getIssuedQty());
                a[1] += nz(i.getIssuedQty()) * nz(i.getIssuePrice());
            }
        }
        List<Map<String, Object>> issuedRows = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : issuedAgg.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", e.getKey());
            row.put("materialName", name(mats, e.getKey()));
            row.put("unit", unit(mats, e.getKey()));
            row.put("issuedQty", MasterDataService.round(e.getValue()[0]));
            row.put("issuedAmount", MasterDataService.round(e.getValue()[1]));
            row.put("docs", issuedDocs);
            issuedRows.add(row);
        }

        // 现场库存与可用批次
        List<MaterialBatch> batches = v.getMaterialId() == null ? List.of()
                : batchRepo.findByMaterialIdOrderByInboundTimeDesc(v.getMaterialId()).stream()
                .filter(b -> !"FAILED".equals(b.getQcStatus())).toList();
        double onHand = batches.stream().mapToDouble(MaterialBatch::onHand).sum();

        // 已有关联追加领料
        List<Map<String, Object>> additions = reqRepo.findAllWithItems().stream()
                .filter(r -> r.isChangeAdditional() && Objects.equals(r.getVisaId(), v.getId()))
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.getId());
                    row.put("code", r.getCode());
                    row.put("status", r.getStatus());
                    row.put("costBearing", r.getCostBearing());
                    row.put("approvedBy", r.getApprovedBy());
                    row.put("issuedBy", r.getIssuedBy());
                    double material = r.getItems().stream()
                            .mapToDouble(i -> nz(i.getIssuedQty()) * nz(i.getIssuePrice())).sum();
                    row.put("materialAmount", MasterDataService.round(material));
                    row.put("laborAmount", MasterDataService.round(
                            nz(r.getExtraLaborQty()) * nz(r.getExtraLaborPrice())));
                    return row;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visa", v);
        out.put("budgets", budgets);
        out.put("issued", issuedRows);
        out.put("onHandQty", MasterDataService.round(onHand));
        out.put("batches", batches.stream().map(b -> Map.of(
                "batchId", b.getId(), "batchNo", b.getBatchNo(),
                "onHand", b.onHand(), "unitPrice", b.getUnitPrice(),
                "qcStatus", b.getQcStatus())).toList());
        out.put("additions", additions);
        return out;
    }

    private static String name(Map<Long, Material> mats, Long id) {
        Material m = mats.get(id);
        return m == null ? null : m.getName();
    }

    private static String unit(Map<Long, Material> mats, Long id) {
        Material m = mats.get(id);
        return m == null ? null : m.getUnit();
    }

    private static double nz(Double x) {
        return x == null ? 0d : x;
    }
}

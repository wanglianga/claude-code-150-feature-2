package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.*;
import com.site.material.dto.Requests.*;
import com.site.material.repo.*;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 变更追加领料链路：
 *  班组关联【变更签证 + 原预算 + 已领材料 + 新增需求】发起追加
 *  -> 平台汇总核对上下文 -> 项目经理审核并判定成本承担方（可签证 / 施工返工 / 项目承担）
 *  -> 自动生成追加领料单（绑定变更编号）-> 仓管核对批次并发料
 *  -> 平台把新增材料与变更编号绑定，财务月底直接区分合同内成本与签证成本。
 */
@Service
public class SupplementService {

    private final SupplementRepository suppRepo;
    private final ChangeVisaRepository visaRepo;
    private final RequisitionRepository reqRepo;
    private final MaterialRepository materialRepo;
    private final MaterialBatchRepository batchRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final CrewRepository crewRepo;
    private final CodeGenerator codes;

    public SupplementService(SupplementRepository suppRepo, ChangeVisaRepository visaRepo,
                             RequisitionRepository reqRepo, MaterialRepository materialRepo,
                             MaterialBatchRepository batchRepo, ProjectBudgetRepository budgetRepo,
                             CrewRepository crewRepo, CodeGenerator codes) {
        this.suppRepo = suppRepo;
        this.visaRepo = visaRepo;
        this.reqRepo = reqRepo;
        this.materialRepo = materialRepo;
        this.batchRepo = batchRepo;
        this.budgetRepo = budgetRepo;
        this.crewRepo = crewRepo;
        this.codes = codes;
    }

    @Transactional
    public MaterialSupplement create(SupplementRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("追加材料明细不能为空");
        }
        ChangeVisa visa = visaRepo.findById(req.visaId())
                .orElseThrow(() -> new BusinessException("关联的变更签证不存在"));
        crewRepo.findById(req.crewId()).orElseThrow(() -> new BusinessException("班组不存在"));
        checkBearing(req.costBearing());
        for (SupplementItemIn it : req.items()) {
            materialRepo.findById(it.materialId()).orElseThrow(() -> new BusinessException("材料不存在"));
            if (it.applyQty() == null || it.applyQty() <= 0) {
                throw new BusinessException("追加数量必须大于 0");
            }
        }

        MaterialSupplement s = new MaterialSupplement();
        s.setCode(codes.next("ZJ"));
        s.setVisaId(visa.getId());
        s.setCrewId(req.crewId());
        s.setBuilding(req.building());
        s.setArea(req.area());
        s.setProcess(req.process());
        s.setDrawingNo(req.drawingNo());
        s.setReason(req.reason());
        s.setCostBearing(req.costBearing());
        s.setLaborCost(req.laborCost() == null ? 0d : req.laborCost());
        s.setStatus("SUBMITTED");
        s.setApplicant(SecurityUtil.currentName());
        s.setApplyTime(LocalDateTime.now());
        for (SupplementItemIn it : req.items()) {
            SupplementItem item = new SupplementItem();
            item.setSupplement(s);
            item.setMaterialId(it.materialId());
            item.setApplyQty(it.applyQty());
            item.setUsage(it.usage());
            s.getItems().add(item);
        }
        return suppRepo.save(s);
    }

    /** 审核前核对上下文：变更信息、原预算、已领材料、现场库存、新增需求 */
    public Map<String, Object> context(Long id) {
        MaterialSupplement s = suppRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("追加领料申请不存在"));
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        ChangeVisa visa = visaRepo.findById(s.getVisaId()).orElse(null);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SupplementItem it : s.getItems()) {
            Material m = mats.get(it.getMaterialId());
            List<MaterialBatch> batches = batchRepo.findByMaterialIdOrderByInboundTimeDesc(it.getMaterialId())
                    .stream().filter(b -> !"FAILED".equals(b.getQcStatus())).toList();
            double onHand = batches.stream().mapToDouble(MaterialBatch::onHand).sum();

            List<ProjectBudget> budgets = budgetRepo.findByBuildingAndProcessAndMaterialId(
                    s.getBuilding(), s.getProcess(), it.getMaterialId());
            double budgetQty = budgets.stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(s.getCrewId()))
                    .mapToDouble(ProjectBudget::getBudgetQty).sum();
            double budgetAmt = budgets.stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(s.getCrewId()))
                    .mapToDouble(b -> b.getBudgetQty() * nz(b.getBudgetPrice())).sum();
            double alreadyIssued = issuedAlready(s.getCrewId(), s.getBuilding(), s.getProcess(), it.getMaterialId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", it.getId());
            row.put("materialId", it.getMaterialId());
            row.put("materialName", m == null ? null : m.getName());
            row.put("spec", m == null ? null : m.getSpec());
            row.put("unit", m == null ? null : m.getUnit());
            row.put("applyQty", it.getApplyQty());
            row.put("usage", it.getUsage());
            row.put("budgetQty", MasterDataService.round(budgetQty));
            row.put("budgetAmount", MasterDataService.round(budgetAmt));
            row.put("alreadyIssued", MasterDataService.round(alreadyIssued));
            row.put("onHandQty", MasterDataService.round(onHand));
            row.put("stockEnough", onHand + 1e-9 >= it.getApplyQty());
            row.put("batches", batches.stream().map(b -> Map.of(
                    "batchId", b.getId(), "batchNo", b.getBatchNo(),
                    "onHand", b.onHand(), "unitPrice", b.getUnitPrice(),
                    "qcStatus", b.getQcStatus())).toList());
            rows.add(row);
        }

        // 同楼栋/工序/班组的历史领料（已领材料口径）
        List<Map<String, Object>> history = reqRepo.findAllWithItems().stream()
                .filter(r -> "ISSUED".equals(r.getStatus()))
                .filter(r -> r.getCrewId().equals(s.getCrewId()))
                .filter(r -> Objects.equals(r.getBuilding(), s.getBuilding()))
                .filter(r -> Objects.equals(r.getProcess(), s.getProcess()))
                .map(r -> {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("requisitionId", r.getId());
                    h.put("code", r.getCode());
                    h.put("visaId", r.getVisaId());
                    h.put("costBearing", r.getCostBearing());
                    h.put("issueTime", r.getIssueTime());
                    h.put("items", r.getItems().stream().map(i -> Map.of(
                            "materialId", i.getMaterialId(),
                            "materialName", mats.get(i.getMaterialId()) == null ? null
                                    : mats.get(i.getMaterialId()).getName(),
                            "issuedQty", nz(i.getIssuedQty()))).toList());
                    return h;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supplement", view(s));
        out.put("visa", visa == null ? null : visaView(visa));
        out.put("checks", rows);
        out.put("issuedHistory", history);
        return out;
    }

    /** 项目经理审核：通过则生成追加领料单并把材料与变更编号绑定 */
    @Transactional
    public MaterialSupplement review(Long id, SupplementReviewRequest req) {
        MaterialSupplement s = suppRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("追加领料申请不存在"));
        if (!"SUBMITTED".equals(s.getStatus())) {
            throw new BusinessException("仅待审核追加申请可审核，当前状态: " + s.getStatus());
        }
        if (!req.approved()) {
            s.setStatus("REJECTED");
            s.setReviewer(SecurityUtil.currentName());
            s.setReviewRemark(req.reviewRemark());
            s.setReviewTime(LocalDateTime.now());
            return suppRepo.save(s);
        }

        String bearing = req.costBearing() == null ? s.getCostBearing() : req.costBearing();
        checkBearing(bearing);
        ChangeVisa visa = visaRepo.findById(s.getVisaId())
                .orElseThrow(() -> new BusinessException("关联的变更签证不存在"));

        // 生成追加领料单（随单绑定变更编号与成本承担方），进入仓管发料环节
        Requisition r = new Requisition();
        r.setCode(codes.next("LZ"));
        r.setCrewId(s.getCrewId());
        r.setBuilding(s.getBuilding());
        r.setArea(s.getArea());
        r.setProcess(s.getProcess());
        r.setDrawingNo(s.getDrawingNo());
        r.setStatus("SUBMITTED");
        r.setUrgent(false);
        r.setApplicant(s.getApplicant());
        r.setApplyTime(LocalDateTime.now());
        r.setSupplementId(s.getId());
        r.setVisaId(visa.getId());
        r.setCostBearing(bearing);
        r.setRemark("变更[" + visa.getCode() + "]追加领料：" + nz(s.getReason()));
        for (SupplementItem it : s.getItems()) {
            RequisitionItem ri = new RequisitionItem();
            ri.setRequisition(r);
            ri.setMaterialId(it.getMaterialId());
            ri.setApplyQty(it.getApplyQty());
            r.getItems().add(ri);
        }
        reqRepo.save(r);

        s.setStatus("APPROVED");
        s.setCostBearing(bearing);
        s.setReviewer(SecurityUtil.currentName());
        s.setReviewRemark(req.reviewRemark());
        s.setReviewTime(LocalDateTime.now());
        s.setRequisitionId(r.getId());
        suppRepo.save(s);

        // 平台把新增材料需求与变更编号绑定，材料成本进入该签证索赔清单
        visa.setSupplementId(s.getId());
        visaRepo.save(visa);
        return s;
    }

    /** 仓管发料完成后回写追加申请状态 */
    @Transactional
    public void markIssued(Long supplementId) {
        if (supplementId == null) return;
        suppRepo.findById(supplementId).ifPresent(s -> {
            if ("APPROVED".equals(s.getStatus())) s.setStatus("ISSUED");
        });
    }

    public List<Map<String, Object>> list(String status, Long crewId) {
        return suppRepo.findAllWithItems().stream()
                .filter(s -> status == null || status.equals(s.getStatus()))
                .filter(s -> crewId == null || crewId.equals(s.getCrewId()))
                .map(this::view)
                .toList();
    }

    public Map<String, Object> detail(Long id) {
        MaterialSupplement s = suppRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("追加领料申请不存在"));
        return view(s);
    }

    public Map<String, Object> view(MaterialSupplement s) {
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> batchNos = batchRepo.findAll().stream()
                .collect(Collectors.toMap(MaterialBatch::getId, MaterialBatch::getBatchNo));
        ChangeVisa visa = s.getVisaId() == null ? null : visaRepo.findById(s.getVisaId()).orElse(null);
        Requisition req = s.getRequisitionId() == null ? null
                : reqRepo.findById(s.getRequisitionId()).orElse(null);

        double materialAmount = 0d;
        List<Map<String, Object>> items = new ArrayList<>();
        if (req != null) {
            List<RequisitionItem> ris = reqRepo.findByIdWithItems(req.getId())
                    .map(Requisition::getItems).orElse(List.of());
            for (RequisitionItem ri : ris) {
                materialAmount += nz(ri.getIssuedQty()) * nz(ri.getIssuePrice());
            }
            for (RequisitionItem ri : ris) {
                Material m = mats.get(ri.getMaterialId());
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("materialId", ri.getMaterialId());
                x.put("materialName", m == null ? null : m.getName());
                x.put("unit", m == null ? null : m.getUnit());
                x.put("applyQty", ri.getApplyQty());
                x.put("issuedQty", nz(ri.getIssuedQty()));
                x.put("batchId", ri.getBatchId());
                x.put("batchNo", ri.getBatchId() == null ? null : batchNos.get(ri.getBatchId()));
                x.put("issuePrice", nz(ri.getIssuePrice()));
                x.put("amount", MasterDataService.round(nz(ri.getIssuedQty()) * nz(ri.getIssuePrice())));
                items.add(x);
            }
        } else {
            for (SupplementItem it : s.getItems()) {
                Material m = mats.get(it.getMaterialId());
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("materialId", it.getMaterialId());
                x.put("materialName", m == null ? null : m.getName());
                x.put("unit", m == null ? null : m.getUnit());
                x.put("applyQty", it.getApplyQty());
                x.put("issuedQty", 0d);
                x.put("usage", it.getUsage());
                x.put("amount", 0d);
                items.add(x);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("code", s.getCode());
        m.put("visaId", s.getVisaId());
        m.put("visaCode", visa == null ? null : visa.getCode());
        m.put("visaChangeType", visa == null ? null : visa.getChangeType());
        m.put("crewId", s.getCrewId());
        m.put("crewName", crewNames.get(s.getCrewId()));
        m.put("building", s.getBuilding());
        m.put("area", s.getArea());
        m.put("process", s.getProcess());
        m.put("drawingNo", s.getDrawingNo());
        m.put("reason", s.getReason());
        m.put("costBearing", s.getCostBearing());
        m.put("laborCost", MasterDataService.round(nz(s.getLaborCost())));
        m.put("status", s.getStatus());
        m.put("applicant", s.getApplicant());
        m.put("reviewer", s.getReviewer());
        m.put("reviewRemark", s.getReviewRemark());
        m.put("applyTime", s.getApplyTime());
        m.put("reviewTime", s.getReviewTime());
        m.put("requisitionId", s.getRequisitionId());
        m.put("requisitionCode", req == null ? null : req.getCode());
        m.put("requisitionStatus", req == null ? null : req.getStatus());
        m.put("items", items);
        m.put("materialAmount", MasterDataService.round(materialAmount));
        m.put("totalAmount", MasterDataService.round(materialAmount + nz(s.getLaborCost())));
        return m;
    }

    private Map<String, Object> visaView(ChangeVisa v) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", v.getId());
        x.put("code", v.getCode());
        x.put("changeType", v.getChangeType());
        x.put("description", v.getDescription());
        x.put("qtyChange", v.getQtyChange());
        x.put("unitPrice", v.getUnitPrice());
        x.put("claimAmount", v.getClaimAmount());
        x.put("status", v.getStatus());
        return x;
    }

    private double issuedAlready(Long crewId, String building, String process, Long materialId) {
        return reqRepo.findAllWithItems().stream()
                .filter(x -> "ISSUED".equals(x.getStatus()))
                .filter(x -> x.getCrewId().equals(crewId))
                .filter(x -> Objects.equals(x.getBuilding(), building))
                .filter(x -> Objects.equals(x.getProcess(), process))
                .flatMap(x -> x.getItems().stream())
                .filter(i -> i.getMaterialId().equals(materialId))
                .mapToDouble(i -> nz(i.getIssuedQty()))
                .sum();
    }

    private void checkBearing(String bearing) {
        if (!Set.of("VISA", "CREW", "COMPANY").contains(bearing)) {
            throw new BusinessException("成本承担方只能是 VISA(可签证) / CREW(施工返工) / COMPANY(项目承担)");
        }
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}

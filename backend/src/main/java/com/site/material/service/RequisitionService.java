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
 * 领料链路：班组申请 -> 仓管核对（施工进度/预算量/已领量/现场库存）-> 发料 -> 班组长确认。
 */
@Service
public class RequisitionService {

    private final RequisitionRepository reqRepo;
    private final MaterialBatchRepository batchRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final MaterialRepository materialRepo;
    private final CrewRepository crewRepo;
    private final ChangeVisaRepository visaRepo;
    private final CodeGenerator codes;

    public RequisitionService(RequisitionRepository reqRepo, MaterialBatchRepository batchRepo,
                              ProjectBudgetRepository budgetRepo, MaterialRepository materialRepo,
                              CrewRepository crewRepo, ChangeVisaRepository visaRepo,
                              CodeGenerator codes) {
        this.reqRepo = reqRepo;
        this.batchRepo = batchRepo;
        this.budgetRepo = budgetRepo;
        this.materialRepo = materialRepo;
        this.crewRepo = crewRepo;
        this.visaRepo = visaRepo;
        this.codes = codes;
    }

    @Transactional
    public Requisition create(RequisitionRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("领料明细不能为空");
        }
        crewRepo.findById(req.crewId()).orElseThrow(() -> new BusinessException("班组不存在"));
        Requisition r = new Requisition();
        r.setCode(codes.next("LL"));
        r.setCrewId(req.crewId());
        r.setBuilding(req.building());
        r.setArea(req.area());
        r.setProcess(req.process());
        r.setDrawingNo(req.drawingNo());
        r.setUrgent(req.urgent());
        r.setRemark(req.remark());
        r.setApplicant(SecurityUtil.currentName());
        r.setApplyTime(LocalDateTime.now());
        r.setStatus("SUBMITTED"); // 班组申请即提交
        for (ItemIn it : req.items()) {
            RequisitionItem item = new RequisitionItem();
            item.setRequisition(r);
            item.setMaterialId(it.materialId());
            item.setApplyQty(it.applyQty());
            r.getItems().add(item);
        }
        return reqRepo.save(r);
    }

    /**
     * 变更追加领料：关联设计变更、原预算、已领材料与新增需求，提交后待项目经理审核。
     * 成本归属：VISA_CLAIM 可向甲方签证（材料进索赔清单）；REWORK 施工返工（计责任班组）。
     */
    @Transactional
    public Requisition createAdditional(AdditionalRequisitionRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("追加材料明细不能为空");
        }
        ChangeVisa visa = visaRepo.findById(req.visaId())
                .orElseThrow(() -> new BusinessException("设计变更不存在"));
        crewRepo.findById(req.crewId()).orElseThrow(() -> new BusinessException("班组不存在"));
        if (!"VISA_CLAIM".equals(req.costBearing()) && !"REWORK".equals(req.costBearing())) {
            throw new BusinessException("成本归属只能是 VISA_CLAIM（签证索赔）或 REWORK（施工返工）");
        }
        Requisition r = new Requisition();
        r.setCode(codes.next("LZ")); // 追加领料前缀
        r.setCrewId(req.crewId());
        r.setBuilding(req.building());
        r.setArea(req.area());
        r.setProcess(req.process());
        r.setDrawingNo(req.drawingNo());
        r.setChangeAdditional(true);
        r.setVisaId(visa.getId());
        r.setCostBearing(req.costBearing());
        r.setExtraLaborQty(req.extraLaborQty() == null ? 0d : req.extraLaborQty());
        r.setExtraLaborPrice(req.extraLaborPrice() == null ? 0d : req.extraLaborPrice());
        r.setRemark(req.remark());
        r.setApplicant(SecurityUtil.currentName());
        r.setApplyTime(LocalDateTime.now());
        r.setStatus("SUBMITTED");
        // 明细写入原预算量/已领量快照，供项目经理审核时对照新增需求
        for (AdditionalItemIn it : req.items()) {
            materialRepo.findById(it.materialId())
                    .orElseThrow(() -> new BusinessException("材料不存在"));
            double budgetQty = budgetRepo.findByBuildingAndProcessAndMaterialId(
                            r.getBuilding(), r.getProcess(), it.materialId()).stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(r.getCrewId()))
                    .mapToDouble(ProjectBudget::getBudgetQty).sum();
            RequisitionItem item = new RequisitionItem();
            item.setRequisition(r);
            item.setMaterialId(it.materialId());
            item.setApplyQty(it.applyQty());
            item.setBudgetQty(MasterDataService.round(budgetQty));
            item.setAlreadyIssuedQty(MasterDataService.round(issuedAlready(r, it.materialId())));
            r.getItems().add(item);
        }
        return reqRepo.save(r);
    }

    /** 项目经理审核追加领料：通过后进入待发料（APPROVED），仓管按正常流程核对发料 */
    @Transactional
    public Requisition approve(Long id, String remark) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        if (!r.isChangeAdditional()) {
            throw new BusinessException("仅变更追加领料需要项目经理审核");
        }
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new BusinessException("仅待审核的追加领料可审核，当前状态: " + r.getStatus());
        }
        r.setStatus("APPROVED");
        r.setApprovedBy(SecurityUtil.currentName());
        r.setApprovedTime(LocalDateTime.now());
        if (remark != null) {
            r.setRemark((r.getRemark() == null ? "" : r.getRemark() + "｜") + "PM审核:" + remark);
        }
        return reqRepo.save(r);
    }

    /** 发料前核对：预算量、已领量、现场库存（给前端展示核对结果，不直接改库存） */
    public Map<String, Object> preIssueCheck(Long id) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RequisitionItem it : r.getItems()) {
            Material m = mats.get(it.getMaterialId());
            List<MaterialBatch> batches = batchRepo.findByMaterialIdOrderByInboundTimeDesc(it.getMaterialId())
                    .stream().filter(b -> !"FAILED".equals(b.getQcStatus())).toList();
            double onHand = batches.stream().mapToDouble(MaterialBatch::onHand).sum();

            List<ProjectBudget> budgets = budgetRepo.findByBuildingAndProcessAndMaterialId(
                    r.getBuilding(), r.getProcess(), it.getMaterialId());
            double budgetQty = budgets.stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(r.getCrewId()))
                    .mapToDouble(ProjectBudget::getBudgetQty).sum();
            double alreadyIssued = issuedAlready(r, it.getMaterialId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", it.getId());
            row.put("materialId", it.getMaterialId());
            row.put("materialName", m == null ? null : m.getName());
            row.put("unit", m == null ? null : m.getUnit());
            row.put("applyQty", it.getApplyQty());
            row.put("budgetQty", MasterDataService.round(budgetQty));
            row.put("alreadyIssued", MasterDataService.round(alreadyIssued));
            row.put("onHandQty", MasterDataService.round(onHand));
            row.put("stockEnough", onHand + 1e-9 >= it.getApplyQty());
            row.put("overBudget", budgetQty > 0 && alreadyIssued + it.getApplyQty() > budgetQty + 1e-9);
            row.put("batches", batches.stream().map(b -> Map.of(
                    "batchId", b.getId(), "batchNo", b.getBatchNo(),
                    "onHand", b.onHand(), "unitPrice", b.getUnitPrice(),
                    "qcStatus", b.getQcStatus())).toList());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requisition", view(r));
        out.put("checks", rows);
        return out;
    }

    private double issuedAlready(Requisition current, Long materialId) {
        return reqRepo.findAllWithItems().stream()
                .filter(x -> "ISSUED".equals(x.getStatus()))
                .filter(x -> x.getCrewId().equals(current.getCrewId()))
                .filter(x -> Objects.equals(x.getBuilding(), current.getBuilding()))
                .filter(x -> Objects.equals(x.getProcess(), current.getProcess()))
                .flatMap(x -> x.getItems().stream())
                .filter(i -> i.getMaterialId().equals(materialId))
                .mapToDouble(i -> i.getIssuedQty() == null ? 0d : i.getIssuedQty())
                .sum();
    }

    @Transactional
    public Map<String, Object> issue(Long id, IssueRequest req) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        boolean additional = r.isChangeAdditional();
        String expected = additional ? "APPROVED" : "SUBMITTED";
        if (!expected.equals(r.getStatus())) {
            throw new BusinessException(additional
                    ? "追加领料须经项目经理审核通过后才能发料，当前状态: " + r.getStatus()
                    : "仅待发料单据可执行发料，当前状态: " + r.getStatus());
        }
        Map<Long, IssueItemIn> input = req.items().stream()
                .collect(Collectors.toMap(IssueItemIn::itemId, Function.identity()));
        List<String> warnings = new ArrayList<>();

        for (RequisitionItem item : r.getItems()) {
            IssueItemIn in = input.get(item.getId());
            if (in == null || in.issuedQty() == null || in.issuedQty() <= 0) {
                throw new BusinessException("明细 " + item.getId() + " 未填写实发数量");
            }
            if (in.batchId() == null) {
                throw new BusinessException("请选择发料批次");
            }
            MaterialBatch batch = batchRepo.findById(in.batchId())
                    .orElseThrow(() -> new BusinessException("批次不存在"));
            if (!batch.getMaterialId().equals(item.getMaterialId())) {
                throw new BusinessException("批次材料与领料明细不一致");
            }
            if ("FAILED".equals(batch.getQcStatus()) || "PENDING".equals(batch.getQcStatus())) {
                throw new BusinessException("批次 " + batch.getBatchNo() + " 质检状态为 "
                        + batch.getQcStatus() + "，不能发料");
            }
            if (batch.onHand() + 1e-9 < in.issuedQty()) {
                throw new BusinessException("批次 " + batch.getBatchNo() + " 现场库存不足："
                        + MasterDataService.round(batch.onHand()));
            }
            batch.setQtyIssued(batch.getQtyIssued() + in.issuedQty());
            item.setBatchId(batch.getId());
            item.setIssuedQty(in.issuedQty());
            item.setIssuePrice(batch.getUnitPrice());

            // 追加领料已经项目经理审核（授权超原预算），不再给超预算预警
            if (!additional && !r.isUrgent()) {
                double already = issuedAlready(r, item.getMaterialId());
                double budgetQty = budgetRepo.findByBuildingAndProcessAndMaterialId(
                                r.getBuilding(), r.getProcess(), item.getMaterialId()).stream()
                        .filter(b -> b.getCrewId() == null || b.getCrewId().equals(r.getCrewId()))
                        .mapToDouble(ProjectBudget::getBudgetQty).sum();
                if (budgetQty > 0 && already + in.issuedQty() > budgetQty + 1e-9) {
                    warnings.add("材料 " + item.getMaterialId() + " 累计领用已超预算，需补办审批");
                }
            }
        }
        r.setStatus("ISSUED");
        r.setIssuedBy(SecurityUtil.currentName());
        r.setConfirmedBy(r.getApplicant()); // 班组长确认领用
        r.setIssueTime(LocalDateTime.now());
        if (additional && r.getVisaId() != null) {
            warnings.add(0, "追加领料已与变更编号 " + visaCode(r.getVisaId()) + " 绑定，"
                    + ("VISA_CLAIM".equals(r.getCostBearing())
                            ? "材料与人工成本进入签证索赔清单"
                            : "材料与人工成本计入责任班组（施工返工）"));
        }
        if (req.remark() != null) {
            r.setRemark(req.remark());
        }
        reqRepo.save(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requisition", view(r));
        out.put("warnings", warnings);
        return out;
    }

    private String visaCode(Long visaId) {
        return visaRepo.findById(visaId).map(ChangeVisa::getCode).orElse(String.valueOf(visaId));
    }

    @Transactional
    public Requisition reject(Long id, String remark) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new BusinessException("仅待发料单据可驳回");
        }
        r.setStatus("REJECTED");
        r.setRemark(remark);
        return r;
    }

    public List<Map<String, Object>> list(String status, Long crewId) {
        List<Requisition> all = reqRepo.findAllWithItems();
        return all.stream()
                .filter(r -> status == null || status.equals(r.getStatus()))
                .filter(r -> crewId == null || crewId.equals(r.getCrewId()))
                .map(this::view)
                .toList();
    }

    public Map<String, Object> detail(Long id) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        return view(r);
    }

    public Map<String, Object> view(Requisition r) {
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> units = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getUnit));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> batchNos = batchRepo.findAll().stream()
                .collect(Collectors.toMap(MaterialBatch::getId, MaterialBatch::getBatchNo));
        Map<Long, ChangeVisa> visas = visaRepo.findAll().stream()
                .collect(Collectors.toMap(ChangeVisa::getId, v -> v));

        List<Map<String, Object>> items = r.getItems().stream().map(i -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("itemId", i.getId());
            x.put("materialId", i.getMaterialId());
            x.put("materialName", matNames.get(i.getMaterialId()));
            x.put("unit", units.get(i.getMaterialId()));
            x.put("applyQty", i.getApplyQty());
            x.put("budgetQty", i.getBudgetQty());
            x.put("alreadyIssuedQty", i.getAlreadyIssuedQty());
            x.put("issuedQty", i.getIssuedQty());
            x.put("batchId", i.getBatchId());
            x.put("batchNo", i.getBatchId() == null ? null : batchNos.get(i.getBatchId()));
            x.put("issuePrice", i.getIssuePrice());
            x.put("amount", MasterDataService.round(
                    (i.getIssuedQty() == null ? 0d : i.getIssuedQty()) * i.getIssuePrice()));
            return x;
        }).toList();

        double materialAmount = items.stream()
                .mapToDouble(x -> (double) x.get("amount")).sum();
        double laborAmount = nz(r.getExtraLaborQty()) * nz(r.getExtraLaborPrice());

        ChangeVisa visa = r.getVisaId() == null ? null : visas.get(r.getVisaId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("crewId", r.getCrewId());
        m.put("crewName", crewNames.get(r.getCrewId()));
        m.put("building", r.getBuilding());
        m.put("area", r.getArea());
        m.put("process", r.getProcess());
        m.put("drawingNo", r.getDrawingNo());
        m.put("status", r.getStatus());
        m.put("urgent", r.isUrgent());
        m.put("changeAdditional", r.isChangeAdditional());
        m.put("visaId", r.getVisaId());
        m.put("visaCode", visa == null ? null : visa.getCode());
        m.put("visaChangeType", visa == null ? null : visa.getChangeType());
        m.put("costBearing", r.getCostBearing());
        m.put("extraLaborQty", r.getExtraLaborQty());
        m.put("extraLaborPrice", r.getExtraLaborPrice());
        m.put("extraLaborAmount", MasterDataService.round(laborAmount));
        m.put("applicant", r.getApplicant());
        m.put("approvedBy", r.getApprovedBy());
        m.put("approvedTime", r.getApprovedTime());
        m.put("issuedBy", r.getIssuedBy());
        m.put("confirmedBy", r.getConfirmedBy());
        m.put("applyTime", r.getApplyTime());
        m.put("issueTime", r.getIssueTime());
        m.put("remark", r.getRemark());
        m.put("items", items);
        m.put("materialAmount", MasterDataService.round(materialAmount));
        m.put("totalAmount", MasterDataService.round(materialAmount + laborAmount));
        return m;
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }
}

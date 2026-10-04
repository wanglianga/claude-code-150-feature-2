package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.*;
import com.site.material.repo.*;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 月底班组材料核算：按 预算/实际领用/退库/损耗/责任归属 生成班组材料成本与扣款。
 * 计算口径（演示规则，可在 README 查看）：
 *  - 实际消耗 = 已发料金额 - 完好退库金额
 *  - 合同内成本 = 常规领料（无成本承担方）的净消耗；材料偏差 = 合同内成本 - 预算
 *  - 签证成本 = 可向甲方签证的变更追加领料（材料+人工），进入变更索赔清单
 *  - 返工成本 = 施工返工类追加领料（材料+人工），计入责任班组扣款
 *  - 班组扣款 = 班组责任损耗 + 损坏退库 + 正偏差（仅合同内） + 施工返工成本
 *  - 供应商质量损耗单列索赔，不扣班组；设计变更/公司承担损耗单列。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepo;
    private final RequisitionRepository reqRepo;
    private final MaterialReturnRepository returnRepo;
    private final WasteRepository wasteRepo;
    private final ChangeVisaRepository visaRepo;
    private final SupplementRepository suppRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final CrewRepository crewRepo;
    private final CodeGenerator codes;

    public SettlementService(SettlementRepository settlementRepo, RequisitionRepository reqRepo,
                             MaterialReturnRepository returnRepo, WasteRepository wasteRepo,
                             ChangeVisaRepository visaRepo, SupplementRepository suppRepo,
                             ProjectBudgetRepository budgetRepo,
                             CrewRepository crewRepo, CodeGenerator codes) {
        this.settlementRepo = settlementRepo;
        this.reqRepo = reqRepo;
        this.returnRepo = returnRepo;
        this.wasteRepo = wasteRepo;
        this.visaRepo = visaRepo;
        this.suppRepo = suppRepo;
        this.budgetRepo = budgetRepo;
        this.crewRepo = crewRepo;
        this.codes = codes;
    }

    @Transactional
    public Settlement generate(String period, Long crewId) {
        YearMonth ym = YearMonth.parse(period);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();

        Settlement existing = settlementRepo.findByCrewIdAndPeriod(crewId, period).orElse(null);
        if (existing != null && "CONFIRMED".equals(existing.getStatus())) {
            throw new BusinessException("该班组 " + period + " 核算单已确认，不能重复生成");
        }
        Settlement s = existing != null ? existing : new Settlement();
        if (existing == null) {
            s.setCode(codes.next("HS"));
            s.setCrewId(crewId);
            s.setPeriod(period);
        }

        // 预算金额：归口班组或班组共用的预算行
        double budgetAmount = budgetRepo.findAll().stream()
                .filter(b -> b.getCrewId() == null || b.getCrewId().equals(crewId))
                .mapToDouble(b -> b.getBudgetQty() * b.getBudgetPrice()).sum();

        // 实际领用按成本承担方拆分：空=合同内，VISA=签证，CREW=返工，COMPANY=项目承担
        double issuedAmount = 0d;
        double contractIssued = 0d, visaMaterial = 0d, reworkMaterial = 0d, projectBearMaterial = 0d;
        for (Requisition r : reqRepo.findIssuedByCrewBetween(crewId, from, to)) {
            double amt = r.getItems().stream()
                    .mapToDouble(i -> nz(i.getIssuedQty()) * nz(i.getIssuePrice())).sum();
            issuedAmount += amt;
            switch (String.valueOf(r.getCostBearing())) {
                case "VISA" -> visaMaterial += amt;
                case "CREW" -> reworkMaterial += amt;
                case "COMPANY" -> projectBearMaterial += amt;
                default -> contractIssued += amt;
            }
        }

        // 退库：按来源领料单的成本承担方冲减；GOOD 冲减对应成本，DAMAGED 计入班组责任损耗
        double returnedAmount = 0d, damagedAmount = 0d;
        double contractReturned = 0d;
        Map<Long, String> bearingByReq = new HashMap<>();
        for (MaterialReturn r : returnRepo.findConfirmedByCrewBetween(crewId, from, to)) {
            String bearing = r.getRequisitionId() == null ? null
                    : bearingByReq.computeIfAbsent(r.getRequisitionId(), rid ->
                        reqRepo.findById(rid).map(Requisition::getCostBearing).orElse(null));
            for (ReturnItem i : r.getItems()) {
                double amt = i.getQty() * nz(i.getPrice());
                if ("GOOD".equals(i.getConditionType())) {
                    returnedAmount += amt;
                    if (bearing == null) contractReturned += amt;
                    else if ("VISA".equals(bearing)) visaMaterial -= amt;
                    else if ("CREW".equals(bearing)) reworkMaterial -= amt;
                    else if ("COMPANY".equals(bearing)) projectBearMaterial -= amt;
                } else {
                    damagedAmount += amt;
                }
            }
        }
        visaMaterial = Math.max(0d, visaMaterial);
        reworkMaterial = Math.max(0d, reworkMaterial);
        projectBearMaterial = Math.max(0d, projectBearMaterial);

        double contractCost = contractIssued - contractReturned;
        double consumed = issuedAmount - returnedAmount;

        double crewWaste = 0d, supplierWaste = 0d, otherWaste = 0d;
        for (WasteRecord w : wasteRepo.findByCrewBetween(crewId, from, to)) {
            double amt = w.getQty() * nz(w.getUnitPrice());
            switch (String.valueOf(w.getResponsibility())) {
                case "CREW" -> crewWaste += amt;
                case "SUPPLIER" -> supplierWaste += amt;
                default -> otherWaste += amt;
            }
        }
        crewWaste += damagedAmount;

        // 变更追加人工费：审核通过（APPROVED/ISSUED）的追加申请按承担方归集
        double visaLabor = 0d, reworkLabor = 0d, projectBearLabor = 0d;
        for (MaterialSupplement sp : suppRepo.findByCrewBetween(crewId, from, to)) {
            if ("REJECTED".equals(sp.getStatus()) || "SUBMITTED".equals(sp.getStatus())) continue;
            double labor = nz(sp.getLaborCost());
            switch (String.valueOf(sp.getCostBearing())) {
                case "VISA" -> visaLabor += labor;
                case "CREW" -> reworkLabor += labor;
                default -> projectBearLabor += labor;
            }
        }

        // 变更索赔：未绑定追加单的签证按登记金额；已绑定追加单的签证以追加实发材料+人工为准（不重复计）
        double visaClaimBase = visaRepo.findByCrewBetween(crewId, from, to).stream()
                .filter(v -> !"DRAFT".equals(v.getStatus()))
                .filter(v -> v.getSupplementId() == null)
                .mapToDouble(v -> nz(v.getClaimAmount())).sum();
        double claim = visaClaimBase + visaMaterial + visaLabor;

        // 材料偏差只比较合同内成本与原预算，追加成本按责任链路单列，不重复计入偏差
        double variance = contractCost - budgetAmount;
        // 施工返工（材料+人工）计入责任班组扣款
        double deduction = crewWaste + Math.max(0d, variance) + reworkMaterial + reworkLabor;

        s.setBudgetAmount(r2(budgetAmount));
        s.setIssuedAmount(r2(issuedAmount));
        s.setReturnedAmount(r2(returnedAmount));
        s.setConsumedAmount(r2(consumed));
        s.setCrewWasteAmount(r2(crewWaste));
        s.setSupplierWasteAmount(r2(supplierWaste));
        s.setOtherWasteAmount(r2(otherWaste));
        s.setVarianceAmount(r2(variance));
        s.setDeductionAmount(r2(deduction));
        s.setClaimAmount(r2(claim));
        s.setContractCostAmount(r2(contractCost));
        s.setVisaMaterialAmount(r2(visaMaterial));
        s.setVisaLaborAmount(r2(visaLabor));
        s.setReworkMaterialAmount(r2(reworkMaterial));
        s.setReworkLaborAmount(r2(reworkLabor));
        s.setProjectBearAmount(r2(projectBearMaterial + projectBearLabor));
        s.setStatus("DRAFT");
        s.setGeneratedBy(SecurityUtil.currentName());
        s.setCreatedTime(LocalDateTime.now());
        return settlementRepo.save(s);
    }

    /** 一键生成某月所有班组的核算单 */
    @Transactional
    public List<Settlement> generateAll(String period) {
        YearMonth.parse(period);
        List<Settlement> out = new ArrayList<>();
        for (Crew c : crewRepo.findAll()) {
            out.add(generate(period, c.getId()));
        }
        return out;
    }

    @Transactional
    public Settlement confirm(Long id) {
        Settlement s = settlementRepo.findById(id).orElseThrow(() -> new BusinessException("核算单不存在"));
        if (!"DRAFT".equals(s.getStatus())) {
            throw new BusinessException("仅草稿核算单可确认");
        }
        s.setStatus("CONFIRMED");
        s.setConfirmedBy(SecurityUtil.currentName());
        s.setConfirmTime(LocalDateTime.now());
        return settlementRepo.save(s);
    }

    public List<Settlement> list(String period) {
        return period == null ? settlementRepo.findAllByOrderByPeriodDescIdAsc()
                : settlementRepo.findByPeriodOrderByIdAsc(period);
    }

    public Settlement detail(Long id) {
        return settlementRepo.findById(id).orElseThrow(() -> new BusinessException("核算单不存在"));
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }

    private static double r2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}

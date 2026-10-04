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
 *  - 班组扣款 = 班组责任损耗 + 损坏退库 + 超预算消耗（只计正偏差）
 *  - 供应商质量损耗单列索赔，不扣班组；设计变更/公司承担损耗单列。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepo;
    private final RequisitionRepository reqRepo;
    private final MaterialReturnRepository returnRepo;
    private final WasteRepository wasteRepo;
    private final ChangeVisaRepository visaRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final CrewRepository crewRepo;
    private final CodeGenerator codes;

    public SettlementService(SettlementRepository settlementRepo, RequisitionRepository reqRepo,
                             MaterialReturnRepository returnRepo, WasteRepository wasteRepo,
                             ChangeVisaRepository visaRepo, ProjectBudgetRepository budgetRepo,
                             CrewRepository crewRepo, CodeGenerator codes) {
        this.settlementRepo = settlementRepo;
        this.reqRepo = reqRepo;
        this.returnRepo = returnRepo;
        this.wasteRepo = wasteRepo;
        this.visaRepo = visaRepo;
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

        // 实际领用，按成本归属拆分：
        //  普通领料 -> 合同内成本；变更追加 VISA_CLAIM -> 签证成本；变更追加 REWORK -> 班组返工成本
        double issuedAmount = 0d, visaCostAmount = 0d, reworkCostAmount = 0d;
        for (Requisition r : reqRepo.findIssuedByCrewBetween(crewId, from, to)) {
            double material = r.getItems().stream()
                    .mapToDouble(i -> nz(i.getIssuedQty()) * nz(i.getIssuePrice())).sum();
            double labor = nz(r.getExtraLaborQty()) * nz(r.getExtraLaborPrice());
            if (r.isChangeAdditional()) {
                if ("REWORK".equals(r.getCostBearing())) {
                    reworkCostAmount += material + labor;
                } else {
                    visaCostAmount += material + labor;
                }
            } else {
                issuedAmount += material;
            }
        }

        // 退库：GOOD 冲减合同内消耗；DAMAGED 计入班组责任损耗
        double returnedAmount = 0d;
        double damagedAmount = 0d;
        for (MaterialReturn r : returnRepo.findConfirmedByCrewBetween(crewId, from, to)) {
            for (ReturnItem i : r.getItems()) {
                double amt = i.getQty() * nz(i.getPrice());
                if ("GOOD".equals(i.getConditionType())) {
                    returnedAmount += amt;
                } else {
                    damagedAmount += amt;
                }
            }
        }

        // 合同内净消耗（变更追加材料不占用合同预算口径）
        double contractConsumed = issuedAmount - returnedAmount;
        double consumed = contractConsumed + visaCostAmount + reworkCostAmount;

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

        double claim = visaRepo.findByCrewBetween(crewId, from, to).stream()
                .filter(v -> !"DRAFT".equals(v.getStatus()))
                .mapToDouble(v -> nz(v.getClaimAmount())).sum();

        // 偏差只比对合同内口径；施工返工的追加材料与人工计入责任班组扣款
        double variance = contractConsumed - budgetAmount;
        double deduction = crewWaste + Math.max(0d, variance) + reworkCostAmount;

        s.setBudgetAmount(r2(budgetAmount));
        s.setIssuedAmount(r2(issuedAmount + visaCostAmount + reworkCostAmount));
        s.setReturnedAmount(r2(returnedAmount));
        s.setConsumedAmount(r2(consumed));
        s.setCrewWasteAmount(r2(crewWaste));
        s.setSupplierWasteAmount(r2(supplierWaste));
        s.setOtherWasteAmount(r2(otherWaste));
        s.setVarianceAmount(r2(variance));
        s.setDeductionAmount(r2(deduction));
        s.setClaimAmount(r2(claim));
        s.setContractCostAmount(r2(contractConsumed));
        s.setVisaCostAmount(r2(visaCostAmount));
        s.setReworkCostAmount(r2(reworkCostAmount));
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

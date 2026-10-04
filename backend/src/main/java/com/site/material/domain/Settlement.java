package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 班组月度材料核算单：按 班组 + 月份(yyyy-MM) 生成，
 * 汇总预算、实际领用、退库、损耗、责任归属，得出班组材料成本与扣款。
 * 状态：DRAFT -> CONFIRMED(财务确认)
 */
@Getter
@Setter
@Entity
@Table(name = "settlement")
public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @Column(nullable = false)
    private Long crewId;

    /** yyyy-MM */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    private Double budgetAmount = 0d;
    private Double issuedAmount = 0d;
    private Double returnedAmount = 0d;
    /** 实际消耗金额 = 领用 - 退库 */
    private Double consumedAmount = 0d;
    /** 班组责任损耗金额（进入扣款） */
    private Double crewWasteAmount = 0d;
    /** 供应商质量问题金额（向供应商索赔，不扣班组） */
    private Double supplierWasteAmount = 0d;
    /** 设计变更/公司承担金额 */
    private Double otherWasteAmount = 0d;
    /** 材料成本偏差 = 实际消耗 - 预算 */
    private Double varianceAmount = 0d;
    /** 班组材料扣款（责任损耗 + 超耗，按规则） */
    private Double deductionAmount = 0d;
    /** 变更索赔金额（签证） */
    private Double claimAmount = 0d;

    /** 合同内材料成本：普通领料的净消耗（不含变更追加） */
    private Double contractCostAmount = 0d;
    /** 可向甲方签证的变更追加材料成本（含追加人工，进入索赔清单，不扣班组） */
    private Double visaCostAmount = 0d;
    /** 施工返工的变更追加材料成本（含追加人工，计入责任班组） */
    private Double reworkCostAmount = 0d;

    private String generatedBy;
    private String confirmedBy;
    private LocalDateTime createdTime;
    private LocalDateTime confirmTime;
    private String remark;
}

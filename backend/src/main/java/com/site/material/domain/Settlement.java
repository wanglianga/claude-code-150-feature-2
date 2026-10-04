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

    /** 合同内材料净成本（常规领料净消耗，成本承担方为空的领料） */
    private Double contractCostAmount = 0d;
    /** 签证材料成本（可向甲方签证的追加领料实发金额） */
    private Double visaMaterialAmount = 0d;
    /** 签证追加人工费 */
    private Double visaLaborAmount = 0d;
    /** 施工返工材料成本（计入责任班组） */
    private Double reworkMaterialAmount = 0d;
    /** 施工返工追加人工费（计入责任班组） */
    private Double reworkLaborAmount = 0d;
    /** 项目自身承担的追加成本（不可签证、非班组责任） */
    private Double projectBearAmount = 0d;

    private String generatedBy;
    private String confirmedBy;
    private LocalDateTime createdTime;
    private LocalDateTime confirmTime;
    private String remark;
}

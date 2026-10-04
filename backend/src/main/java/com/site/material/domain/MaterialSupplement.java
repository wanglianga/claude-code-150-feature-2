package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 变更追加领料申请：设计/施工变更（如卫生间防水高度临时调整）导致材料与人工追加，
 * 班组在平台上关联 变更签证 + 原预算 + 已领材料 + 新增需求 发起追加，
 * 项目经理审核并判定成本承担方后，自动生成追加领料单（{@link Requisition}）。
 *
 * 成本承担方：
 *  VISA   可向甲方签证 —— 追加材料/人工进入变更索赔清单（合同外签证成本）
 *  CREW   施工返工     —— 追加成本计入责任班组（班组月度扣款）
 *  COMPANY 项目承担    —— 既不索赔也不扣班组
 *
 * 状态流转：SUBMITTED -> APPROVED(PM 审核通过，已生成追加领料单) -> ISSUED(仓管发料完成)
 *                           -> REJECTED(PM 驳回)
 */
@Getter
@Setter
@Entity
@Table(name = "material_supplement")
public class MaterialSupplement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    /** 关联设计/施工变更签证 */
    @Column(nullable = false)
    private Long visaId;

    @Column(nullable = false)
    private Long crewId;

    @Column(nullable = false, length = 32)
    private String building;

    @Column(length = 64)
    private String area;

    @Column(nullable = false, length = 64)
    private String process;

    @Column(length = 64)
    private String drawingNo;

    /** 追加事由（如：卫生间防水高度由 300mm 调整为 1800mm） */
    @Column(length = 1024)
    private String reason;

    /** 成本承担方 VISA / CREW / COMPANY（PM 终核，以审核值为准） */
    @Column(nullable = false, length = 16)
    private String costBearing;

    /** 追加人工费用（元，如防水班组附加层施工人工） */
    @Column(nullable = false)
    private Double laborCost = 0d;

    /** SUBMITTED / APPROVED / ISSUED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status = "SUBMITTED";

    private String applicant;
    private String reviewer;
    private String reviewRemark;
    private LocalDateTime applyTime;
    private LocalDateTime reviewTime;

    /** PM 审核通过后生成的追加领料单 */
    private Long requisitionId;

    @JsonIgnore
    @OneToMany(mappedBy = "supplement", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplementItem> items = new ArrayList<>();
}

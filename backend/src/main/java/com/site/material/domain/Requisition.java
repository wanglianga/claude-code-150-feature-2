package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 领料单：班组按 施工区域/楼栋/工序/图纸 申请领料。
 * 状态流转：
 *  - 常规/夜间紧急：DRAFT -> SUBMITTED -> ISSUED(仓管发料+班长确认) / REJECTED
 *  - 变更追加领料：SUBMITTED -> APPROVED(项目经理审核) -> ISSUED(仓管发料，自动绑定变更编号) / REJECTED
 * 夜间紧急领料走 URGENT 标记，允许先发后补（仍记录在同一条链路）。
 */
@Getter
@Setter
@Entity
@Table(name = "requisition")
public class Requisition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

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

    /** DRAFT / SUBMITTED / APPROVED / ISSUED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    private boolean urgent = false;

    /** 是否为设计/施工变更引起的追加领料 */
    @Column(nullable = false)
    private boolean changeAdditional = false;

    /** 追加领料关联的变更签证（SUBMITTED 申请时即关联；发料后平台确认绑定） */
    private Long visaId;

    /**
     * 追加成本归属：
     * VISA_CLAIM 可向甲方签证，材料成本进入索赔清单；
     * REWORK 施工返工，成本计入责任班组；
     * 普通领料为空。
     */
    @Column(length = 16)
    private String costBearing;

    /** 追加人工（防水班组上翻等），计入签证索赔或班组成本，不扣库存 */
    private Double extraLaborQty = 0d;
    private Double extraLaborPrice = 0d;

    private String applicant;

    /** 班组长确认人 */
    private String confirmedBy;

    /** 变更追加领料的项目经理审核人 */
    private String approvedBy;

    private LocalDateTime approvedTime;

    private String issuedBy;

    private LocalDateTime applyTime;
    private LocalDateTime issueTime;

    private String remark;

    @OneToMany(mappedBy = "requisition", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RequisitionItem> items = new ArrayList<>();
}

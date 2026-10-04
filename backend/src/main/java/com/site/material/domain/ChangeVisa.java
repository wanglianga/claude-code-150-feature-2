package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 变更签证：施工/设计变更引发材料增减与索赔，
 * 与退库（CHANGE）、损耗（DESIGN）挂接，进入财务变更索赔口径。
 * 状态：DRAFT -> CONFIRMED(项目经理/预算确认) -> CLAIMED(财务已索赔)
 */
@Getter
@Setter
@Entity
@Table(name = "change_visa")
public class ChangeVisa {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @Column(nullable = false, length = 32)
    private String building;

    @Column(length = 64)
    private String area;

    @Column(length = 64)
    private String process;

    @Column(length = 64)
    private String drawingNo;

    private Long materialId;
    private Long crewId;

    /** DESIGN 设计变更 / CONSTRUCTION 施工变更 / OWNER 业主要求 */
    @Column(nullable = false, length = 16)
    private String changeType;

    @Column(length = 1024)
    private String description;

    /** 变更引起的材料量变化（正=增加，负=减少） */
    private Double qtyChange = 0d;

    private Double unitPrice = 0d;

    /** 索赔/费用金额（财务口径） */
    private Double claimAmount = 0d;

    /** DRAFT / CONFIRMED / CLAIMED */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    /** 绑定的变更追加领料申请（追加领料通过后回填，材料成本随之进入索赔清单） */
    private Long supplementId;

    private String applicant;
    private String confirmer;
    private LocalDateTime createdTime;
    private LocalDateTime confirmTime;
}

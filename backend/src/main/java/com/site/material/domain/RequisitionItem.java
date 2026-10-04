package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "requisition_item")
public class RequisitionItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requisition_id")
    private Requisition requisition;

    @Column(nullable = false)
    private Long materialId;

    /** 指定批次发料，可空（由仓管分配） */
    private Long batchId;

    /** 申请数量 */
    @Column(nullable = false)
    private Double applyQty;

    /** 申请时该 楼栋/工序/材料 口径的原预算量快照（追加领料核对用） */
    private Double budgetQty = 0d;

    /** 申请时该班组同口径累计已领量快照（追加领料核对用） */
    private Double alreadyIssuedQty = 0d;

    /** 实发数量（发料时填写，用于追加/少发场景） */
    private Double issuedQty = 0d;

    /** 发料单价（取批次移动口径单价快照） */
    private Double issuePrice = 0d;
}

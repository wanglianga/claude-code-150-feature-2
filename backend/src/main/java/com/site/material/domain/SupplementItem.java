package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 变更追加领料明细：新增材料需求（材料 + 追加数量 + 用途说明）。
 */
@Getter
@Setter
@Entity
@Table(name = "supplement_item")
public class SupplementItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplement_id")
    private MaterialSupplement supplement;

    @Column(nullable = false)
    private Long materialId;

    /** 追加申请数量 */
    @Column(nullable = false)
    private Double applyQty;

    /** 用途/部位说明（如：卫生间墙面附加层） */
    @Column(length = 256)
    private String usage;
}

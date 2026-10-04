package com.site.material.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** 全部请求 DTO 的聚合容器 */
public final class Requests {
    private Requests() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record ItemIn(Long materialId, Long batchId,
                         @NotNull @PositiveOrZero Double applyQty,
                         @PositiveOrZero Double issuedQty,
                         @PositiveOrZero Double qty,
                         String conditionType,
                         Double price) {}

    public record RequisitionRequest(
            @NotNull Long crewId,
            @NotBlank String building,
            String area,
            @NotBlank String process,
            String drawingNo,
            boolean urgent,
            String remark,
            @NotNull List<ItemIn> items) {}

    public record IssueItemIn(@NotNull Long itemId, Long batchId,
                              @NotNull @PositiveOrZero Double issuedQty) {}

    public record IssueRequest(@NotNull List<IssueItemIn> items, String remark) {}

    /** 设计/施工变更引起的追加领料（材料追加 + 人工追加） */
    public record AdditionalItemIn(@NotNull Long materialId,
                                   @NotNull @PositiveOrZero Double applyQty) {}

    public record AdditionalRequisitionRequest(
            @NotNull Long visaId,
            @NotNull Long crewId,
            @NotBlank String building,
            String area,
            @NotBlank String process,
            String drawingNo,
            /** VISA_CLAIM 可向甲方签证 / REWORK 施工返工计责任班组 */
            @NotBlank String costBearing,
            @PositiveOrZero Double extraLaborQty,
            @PositiveOrZero Double extraLaborPrice,
            String remark,
            @NotNull List<AdditionalItemIn> items) {}

    public record ReturnRequest(
            @NotNull Long crewId,
            Long requisitionId,
            @NotBlank String building,
            String area,
            String process,
            @NotBlank String reasonType,
            String remark,
            @NotNull List<ItemIn> items) {}

    public record WasteRequest(
            @NotNull Long materialId,
            Long batchId,
            Long crewId,
            @NotBlank String building,
            String area,
            String process,
            @NotNull @PositiveOrZero Double qty,
            @NotBlank String wasteType,
            @NotBlank String responsibility,
            Long visaId,
            String description) {}

    public record VisaRequest(
            @NotBlank String building,
            String area, String process, String drawingNo,
            Long materialId, Long crewId,
            @NotBlank String changeType,
            String description,
            @PositiveOrZero Double qtyChange,
            @PositiveOrZero Double unitPrice,
            @PositiveOrZero Double claimAmount) {}

    public record CheckItemIn(@NotNull Long materialId, Long batchId,
                              @NotNull @PositiveOrZero Double actualQty,
                              String responsibility, String remark) {}

    public record StockCheckRequest(String scope, String remark,
                                    @NotNull List<CheckItemIn> items) {}

    public record InboundRequest(
            @NotNull Long materialId,
            String spec,
            Long supplierId,
            @NotNull @PositiveOrZero Double qtyInbound,
            @PositiveOrZero Double unitPrice,
            String qcStatus,
            String remark) {}

    public record QcRequest(@NotBlank String qcStatus) {}

    public record BudgetRequest(
            @NotBlank String building,
            String area,
            @NotBlank String process,
            String drawingNo,
            @NotNull Long materialId,
            @NotNull @PositiveOrZero Double budgetQty,
            @PositiveOrZero Double budgetPrice,
            Long crewId) {}

    public record MaterialRequest(@NotBlank String name, @NotBlank String category,
                                  String spec, @NotBlank String unit,
                                  Double safetyStock) {}

    public record SupplierRequest(@NotBlank String name, String contact, String phone) {}

    public record CrewRequest(@NotBlank String name, String leader, String trade) {}

    public record GenerateSettlementRequest(String period, Long crewId) {}
}

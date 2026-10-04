package com.site.material.controller;

import com.site.material.domain.MaterialSupplement;
import com.site.material.dto.Requests.SupplementRequest;
import com.site.material.dto.Requests.SupplementReviewRequest;
import com.site.material.service.SupplementService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 变更追加领料：关联设计变更/原预算/已领材料/新增需求，
 * 项目经理审核判定成本承担方（可签证 / 施工返工），生成追加领料单由仓管发料。
 */
@RestController
@RequestMapping("/api/supplements")
public class SupplementController {

    private final SupplementService service;

    public SupplementController(SupplementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long crewId) {
        return service.list(status, crewId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /** 审核前核对上下文：变更签证、原预算、已领材料、现场库存、新增需求 */
    @GetMapping("/{id}/context")
    public Map<String, Object> context(@PathVariable Long id) {
        return service.context(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FOREMAN','KEEPER','PM','BUDGET')")
    public MaterialSupplement create(@Valid @RequestBody SupplementRequest req) {
        return service.create(req);
    }

    /** 项目经理审核：通过即生成追加领料单并绑定变更编号 */
    @PutMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('PM','BUDGET')")
    public MaterialSupplement review(@PathVariable Long id,
                                     @Valid @RequestBody SupplementReviewRequest req) {
        return service.review(id, req);
    }
}

package com.site.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 施工变更导致材料追加 全链路集成测试（H2 + 种子数据）：
 * 防水班组关联设计变更（卫生间防水高度调整）发起追加 ->
 * PM 审核判定【可签证 / 施工返工】-> 生成追加领料单 -> 仓管发料并绑定变更编号 ->
 * 财务月底核算区分合同内成本、签证成本、返工班组承担。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplementFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String token(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return om.readTree(body).get("token").asText();
    }

    private JsonNode json(String resp) throws Exception {
        return om.readTree(resp);
    }

    @Test
    void supplementReviewIssueAndSettlementSplit() throws Exception {
        String pm = token("pm", "pm123");
        String keeper = token("keeper", "keeper123");
        String finance = token("finance", "finance123");
        String foreman4 = token("foreman4", "foreman123"); // 防水班 赵磊
        String foreman5 = token("foreman5", "foreman123"); // 水电班（越权用）

        // 1. 待 PM 审核列表：种子含两张（可签证 zj2 / 施工返工 zj3）
        JsonNode pending = json(mvc.perform(get("/api/supplements?status=SUBMITTED")
                        .header("Authorization", "Bearer " + pm))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long visaSuppId = -1, reworkSuppId = -1;
        for (JsonNode s : pending) {
            if ("VISA".equals(s.get("costBearing").asText())) visaSuppId = s.get("id").asLong();
            if ("CREW".equals(s.get("costBearing").asText())) reworkSuppId = s.get("id").asLong();
        }
        assertTrue(visaSuppId > 0 && reworkSuppId > 0, "应同时存在可签证与施工返工待审核追加单");

        // 2. 审核上下文：关联签证、原预算 400㎡、已领 120㎡（种子已发的追加单 zr1）、库存充足
        JsonNode ctx = json(mvc.perform(get("/api/supplements/" + visaSuppId + "/context")
                        .header("Authorization", "Bearer " + pm))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("QZ20261003003", ctx.get("visa").get("code").asText());
        JsonNode check = ctx.get("checks").get(0);
        assertEquals(400d, check.get("budgetQty").asDouble(), 0.01);
        assertEquals(120d, check.get("alreadyIssued").asDouble(), 0.01);
        assertTrue(check.get("stockEnough").asBoolean());
        assertTrue(ctx.get("issuedHistory").size() >= 1, "应展示同楼栋/工序已领材料");

        // 3. PM 审核可签证追加单：通过，生成追加领料单
        JsonNode approved = json(mvc.perform(put("/api/supplements/" + visaSuppId + "/review")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "approved", true, "costBearing", "VISA", "reviewRemark", "同意按设计变更签证"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("APPROVED", approved.get("status").asText());
        long visaReqId = approved.get("requisitionId").asLong();
        assertTrue(visaReqId > 0);

        // 已审核单不可重复审核
        mvc.perform(put("/api/supplements/" + visaSuppId + "/review")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("approved", true, "costBearing", "VISA"))))
                .andExpect(status().isBadRequest());

        // 4. 仓管对追加领料单核对待发料：不做超预算拦截（变更已由 PM 审核）
        JsonNode visaPre = json(mvc.perform(get("/api/requisitions/" + visaReqId + "/precheck")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode vc = visaPre.get("checks").get(0);
        assertFalse(vc.get("overBudget").asBoolean(), "变更追加单不应按原预算判超预算");
        long visaItemId = vc.get("itemId").asLong();
        long visaBatchId = vc.get("batches").get(0).get("batchId").asLong();

        // 班组长无权发料
        mvc.perform(put("/api/requisitions/" + visaReqId + "/issue")
                        .header("Authorization", "Bearer " + foreman4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", visaItemId, "batchId", visaBatchId, "issuedQty", 80d))))))
                .andExpect(status().isForbidden());

        // 仓管发料 80㎡，新增材料与变更编号绑定
        JsonNode visaIssued = json(mvc.perform(put("/api/requisitions/" + visaReqId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", visaItemId, "batchId", visaBatchId, "issuedQty", 80d))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode vr = visaIssued.get("requisition");
        assertEquals("ISSUED", vr.get("status").asText());
        assertNotNull(vr.get("visaId"));
        assertEquals("VISA", vr.get("costBearing").asText());

        // 追加申请回写为已发料
        JsonNode visaSupp = json(mvc.perform(get("/api/supplements/" + visaSuppId)
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("ISSUED", visaSupp.get("status").asText());
        assertEquals(80d * 32, visaSupp.get("materialAmount").asDouble(), 0.01);

        // 5. PM 审核施工返工追加单：成本计入责任班组
        mvc.perform(put("/api/supplements/" + reworkSuppId + "/review")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "approved", true, "costBearing", "CREW", "reviewRemark", "搭接不达标属施工返工"))))
                .andExpect(status().isOk());
        JsonNode reworkSupp = json(mvc.perform(get("/api/supplements/" + reworkSuppId)
                        .header("Authorization", "Bearer " + pm))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long reworkReqId = reworkSupp.get("requisitionId").asLong();
        JsonNode rePre = json(mvc.perform(get("/api/requisitions/" + reworkReqId + "/precheck")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode rc = rePre.get("checks").get(0);
        long reItemId = rc.get("itemId").asLong();
        long reBatchId = rc.get("batches").get(0).get("batchId").asLong();
        mvc.perform(put("/api/requisitions/" + reworkReqId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", reItemId, "batchId", reBatchId, "issuedQty", 60d))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requisition.costBearing").value("CREW"));

        // 6. 班组长无权审核追加单
        mvc.perform(put("/api/supplements/" + visaSuppId + "/review")
                        .header("Authorization", "Bearer " + foreman5)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("approved", true, "costBearing", "VISA"))))
                .andExpect(status().isForbidden());

        // 7. 新建一张追加单后驳回：不生成领料单
        long visa4Id = 0;
        JsonNode visas = json(mvc.perform(get("/api/visas").header("Authorization", "Bearer " + pm))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        for (JsonNode v : visas) {
            if ("QZ20261003004".equals(v.get("code").asText())) visa4Id = v.get("id").asLong();
        }
        JsonNode created = json(mvc.perform(post("/api/supplements")
                        .header("Authorization", "Bearer " + foreman4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "visaId", visa4Id, "crewId", 4, "building", "2#楼", "area", "卫生间",
                                "process", "防水工程", "drawingNo", "建防-208",
                                "reason", "补报返工材料", "costBearing", "CREW", "laborCost", 0d,
                                "items", List.of(Map.of("materialId", 3, "applyQty", 5d, "usage", "试验"))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long createdId = created.get("id").asLong();
        JsonNode rejected = json(mvc.perform(put("/api/supplements/" + createdId + "/review")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "approved", false, "reviewRemark", "数量存疑，请现场复核"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("REJECTED", rejected.get("status").asText());
        assertTrue(rejected.get("requisitionId").isNull());

        // 非法成本承担方 400
        mvc.perform(post("/api/supplements")
                        .header("Authorization", "Bearer " + foreman4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "visaId", visa4Id, "crewId", 4, "building", "2#楼",
                                "process", "防水工程", "costBearing", "UNKNOWN",
                                "items", List.of(Map.of("materialId", 3, "applyQty", 5d))))))
                .andExpect(status().isBadRequest());

        // 8. 财务月底核算防水班（crewId=4）2026-10
        JsonNode st = json(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("period", "2026-10", "crewId", 4))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        // 合同内：常规领料 300*32 + 30*45 = 10950；完好退库（施工变更退卷材20*32=640）冲减 -> 10310
        assertEquals(10310d, st.get("contractCostAmount").asDouble(), 0.01);
        // 签证材料：种子已发 120*32=3840 + 本测试发料 80*32=2560 = 6400
        assertEquals(6400d, st.get("visaMaterialAmount").asDouble(), 0.01);
        // 签证人工：3600 + 2400 = 6000
        assertEquals(6000d, st.get("visaLaborAmount").asDouble(), 0.01);
        // 返工材料 60*32=1920，返工人工 1800
        assertEquals(1920d, st.get("reworkMaterialAmount").asDouble(), 0.01);
        assertEquals(1800d, st.get("reworkLaborAmount").asDouble(), 0.01);
        // 扣款 = 责任损耗 0 + 正偏差 0 + 返工(1920+1800) = 3720
        assertEquals(3720d, st.get("deductionAmount").asDouble(), 0.01);
        // 对甲方索赔 = 未绑定追加单的签证 v1 登记 6400 + 签证材料 6400 + 签证人工 6000 = 18800
        assertEquals(18800d, st.get("claimAmount").asDouble(), 0.01);
        // 预算：1#地下室 (2600*32+100*45) + 2#卫生间 400*32 = 100500；偏差为负
        assertEquals(100500d, st.get("budgetAmount").asDouble(), 0.01);
        assertTrue(st.get("varianceAmount").asDouble() < 0);

        // 9. 财务汇总：三类成本与追加索赔台账
        JsonNode fin = json(mvc.perform(get("/api/analytics/finance")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(6400d, fin.get("visaMaterialTotal").asDouble(), 0.01);
        assertEquals(6000d, fin.get("visaLaborTotal").asDouble(), 0.01);
        assertEquals(12400d, fin.get("visaTotalCost").asDouble(), 0.01);
        assertEquals(3720d, fin.get("reworkTotalCost").asDouble(), 0.01);
        assertTrue(fin.get("supplements").size() >= 3, "追加领料台账应至少含 3 张有效单");
        // 签证台账：绑定施工返工的签证索赔额为 0，可签证签证按材料+人工列示
        boolean reworkVisaZero = false, visaHeightBound = false;
        for (JsonNode v : fin.get("visas")) {
            if ("QZ20261003004".equals(v.get("code").asText())) {
                reworkVisaZero = v.get("claimAmount").asDouble() == 0d;
            }
            if ("QZ20261003002".equals(v.get("code").asText())) {
                visaHeightBound = v.get("bound").asBoolean()
                        && v.get("materialAmount").asDouble() == 3840d
                        && v.get("laborAmount").asDouble() == 3600d;
            }
        }
        assertTrue(reworkVisaZero, "施工返工签证不计入对甲方索赔");
        assertTrue(visaHeightBound, "防水高度调整签证应绑定追加单并按实际材料+人工列示");

        // 10. 无 token 401
        mvc.perform(get("/api/supplements")).andExpect(status().isUnauthorized());
    }
}

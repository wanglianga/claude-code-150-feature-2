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
 * 全链路集成测试（H2 内存库 + 种子数据）：
 * 登录 -> 发料核对（预算/已领/库存）-> 紧急发料 -> 退库确认 -> 损耗核销 ->
 * 盘点调账 -> 月底核算 -> 偏差/复盘/财务分析。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaterialFlowIntegrationTest {

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

    @Test
    void fullMaterialLifecycleAndSettlement() throws Exception {
        String keeper = token("keeper", "keeper123");
        String finance = token("finance", "finance123");
        String foreman = token("foreman5", "foreman123");

        // 1. 库存总览有数据
        mvc.perform(get("/api/inventory").header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].materialName").exists());

        // 2. 待发料单：夜间紧急单（p3 机电班 DN20 600 米）核对，库存 2650（3000-400+50）
        JsonNode reqs = om.readTree(mvc.perform(get("/api/requisitions?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long urgentId = -1;
        for (JsonNode r : reqs) {
            if (r.get("urgent").asBoolean()) urgentId = r.get("id").asLong();
        }
        assertTrue(urgentId > 0, "应存在夜间紧急领料单");

        JsonNode pre = om.readTree(mvc.perform(get("/api/requisitions/" + urgentId + "/precheck")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode check = pre.get("checks").get(0);
        assertTrue(check.get("stockEnough").asBoolean(), "紧急单库存应充足");
        assertEquals(2000d, check.get("budgetQty").asDouble(), 0.01);
        assertEquals(400d, check.get("alreadyIssued").asDouble(), 0.01);

        // 3. 仓管发料：选择第一个可用批次
        long itemId = check.get("itemId").asLong();
        long batchId = check.get("batches").get(0).get("batchId").asLong();
        String issueBody = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", itemId, "batchId", batchId, "issuedQty", 600d))));
        mvc.perform(put("/api/requisitions/" + urgentId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requisition.status").value("ISSUED"));

        // 4. 库存不足时发料应被拒绝（瓷砖班申请 40 箱，库存 875 箱 => 充足；改用超量构造失败场景：钢筋班 p1 仅 3 吨库存 38 吨，不触发。
        //    直接发一张不存在的超量：用 p1 发 999 吨应报库存不足）
        String p1Raw = mvc.perform(get("/api/requisitions?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode p1List = om.readTree(p1Raw);
        long steelId = -1, steelItem = -1, steelBatch = -1;
        for (JsonNode r : p1List) {
            if ("主体结构".equals(r.get("process").asText()) && r.get("crewId").asLong() == 1) {
                steelId = r.get("id").asLong();
                steelItem = r.get("items").get(0).get("itemId").asLong();
            }
        }
        String steelRaw = mvc.perform(get("/api/requisitions/" + steelId + "/precheck")
                .header("Authorization", "Bearer " + keeper)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode steelPre = om.readTree(steelRaw);
        steelBatch = steelPre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        String badIssue = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 999d))));
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(badIssue))
                .andExpect(status().isBadRequest());

        // 正常发 3 吨（累计 15 < 20 预算，不超）
        String okIssue = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 3d))));
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(okIssue))
                .andExpect(status().isOk());

        // 5. 班组长提交退库，仓管确认入库（规格领错 20 米 DN20）
        String retBody = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "area", "标准层", "process", "机电预埋",
                "reasonType", "SPEC_WRONG",
                "items", List.of(Map.of("materialId", 6, "batchId", batchId, "qty", 20d,
                        "conditionType", "GOOD", "price", 3.5))));
        JsonNode ret = om.readTree(mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(retBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long retId = ret.get("id").asLong();
        mvc.perform(put("/api/returns/" + retId + "/confirm")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 6. 盘点调账：种子盘点单确认后批次库存变化
        JsonNode checks = om.readTree(mvc.perform(get("/api/stock-checks")
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long checkId = checks.get(0).get("id").asLong();
        mvc.perform(put("/api/stock-checks/" + checkId + "/adjust")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADJUSTED"));

        // 7. 月底核算：机电班（crewId=5）2026-10
        String genBody = om.writeValueAsString(Map.of("period", "2026-10", "crewId", 5));
        JsonNode st = om.readTree(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long settlementId = st.get("id").asLong();
        // 机电班领用 400*3.5+800*1.2+紧急600*3.5 = 4460；退库 50*3.5+20*3.5 = 245
        assertEquals(4460d, st.get("issuedAmount").asDouble(), 0.01);
        assertEquals(245d, st.get("returnedAmount").asDouble(), 0.01);
        assertEquals(4215d, st.get("consumedAmount").asDouble(), 0.01);
        // 预算 2000*3.5 + 3000*1.2 = 10600；净消耗低于预算 => 偏差为负，扣款仅责任损耗 0
        assertEquals(-6385d, st.get("varianceAmount").asDouble(), 0.01);
        assertEquals(0d, st.get("deductionAmount").asDouble(), 0.01);

        // 财务确认核算单
        mvc.perform(put("/api/settlements/" + settlementId + "/confirm")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        // 已确认不可重复生成
        mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isBadRequest());

        // 8. 项目经理：按楼栋/工序/班组查看偏差
        mvc.perform(get("/api/analytics/consumption")
                        .param("building", "1#楼").param("crewId", "5")
                        .header("Authorization", "Bearer " + token("pm", "pm123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].netConsumedAmount").exists());

        // 9. 材料员损耗复盘：四类原因汇总，含设计变更与供应商质量
        JsonNode review = om.readTree(mvc.perform(get("/api/analytics/waste-review")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(review.get("totalAmount").asDouble() > 0);
        boolean hasDesign = false, hasQuality = false;
        for (JsonNode s : review.get("summary")) {
            if ("DESIGN".equals(s.get("cause").asText()) && s.get("amount").asDouble() > 0) hasDesign = true;
            if ("QUALITY".equals(s.get("cause").asText()) && s.get("amount").asDouble() > 0) hasQuality = true;
        }
        assertTrue(hasDesign && hasQuality, "复盘应同时包含设计变更与供应商质量损耗");

        // 10. 预算员采购计划 + 财务汇总
        mvc.perform(get("/api/analytics/procurement-plan")
                        .param("building", "1#楼")
                        .header("Authorization", "Bearer " + token("budget", "budget123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].suggestPurchaseQty").exists());
        JsonNode fin = om.readTree(mvc.perform(get("/api/analytics/finance")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(fin.get("supplierTotalPayable").asDouble() > 0);
        assertTrue(fin.get("supplierQualityClaim").asDouble() >= 960); // 30*32

        // 11. 权限：班组长不能登记损耗
        mvc.perform(post("/api/wastes")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "materialId", 4, "building", "1#楼", "qty", 1d,
                                "wasteType", "BREAKAGE", "responsibility", "CREW"))))
                .andExpect(status().isForbidden());

        // 12. 无 token 访问受保护接口 401
        mvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());
    }

    /**
     * 设计临时调整卫生间防水高度：追加卷材+人工 -> 关联设计变更/原预算/已领材料 ->
     * 项目经理审核 -> 仓管发料并绑定变更编号 -> 月底财务区分合同内成本/签证成本/班组返工成本。
     */
    @Test
    void changeAdditionalRequisitionBoundToVisaAndCostSplit() throws Exception {
        String pm = token("pm", "pm123");
        String keeper = token("keeper", "keeper123");
        String finance = token("finance", "finance123");
        String foreman4 = token("foreman4", "foreman123"); // 防水班 赵磊

        // 1. 防水班待项目经理审核的追加领料（卷材 120 ㎡ + 8 工日，快照原预算 180/已领 180）
        JsonNode submitted = om.readTree(mvc.perform(get("/api/requisitions?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long addId = -1;
        for (JsonNode r : submitted) {
            if (r.get("changeAdditional").asBoolean() && "VISA_CLAIM".equals(r.get("costBearing").asText())) {
                addId = r.get("id").asLong();
                assertEquals("QZ20261003002", r.get("visaCode").asText());
                assertEquals(180d, r.get("items").get(0).get("budgetQty").asDouble(), 0.01);
                assertEquals(180d, r.get("items").get(0).get("alreadyIssuedQty").asDouble(), 0.01);
                assertEquals(1600d, r.get("extraLaborAmount").asDouble(), 0.01);
            }
        }
        assertTrue(addId > 0, "应存在卫生间防水设计变更的待审核追加领料");

        // 2. 未经项目经理审核，仓管发料应被拒绝
        JsonNode addPre = om.readTree(mvc.perform(get("/api/requisitions/" + addId + "/precheck")
                        .header("Authorization", "Bearer" + " " + keeper))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long addItem = addPre.get("checks").get(0).get("itemId").asLong();
        long addBatch = addPre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        mvc.perform(put("/api/requisitions/" + addId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", addItem, "batchId", addBatch, "issuedQty", 120d))))))
                .andExpect(status().isBadRequest());

        // 3. 班组长无权审核追加领料
        mvc.perform(put("/api/requisitions/" + addId + "/approve")
                        .header("Authorization", "Bearer " + foreman4))
                .andExpect(status().isForbidden());

        // 4. 项目经理审核通过
        mvc.perform(put("/api/requisitions/" + addId + "/approve")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("remark", "同意按设计变更追加，列入对甲方签证"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("张项目"));

        // 5. 仓管发料：新增材料与变更编号绑定，成本进入签证索赔清单
        JsonNode issueRes = om.readTree(mvc.perform(put("/api/requisitions/" + addId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", addItem, "batchId", addBatch, "issuedQty", 120d))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("ISSUED", issueRes.get("requisition").get("status").asText());
        String warnings = issueRes.get("warnings").toString();
        assertTrue(warnings.contains("QZ20261003002") && warnings.contains("签证"),
                "发料后应提示已绑定变更编号并进入签证索赔: " + warnings);

        // 6. 防水班还可直接基于地下室变更签证发起追加（材料+人工）；非法成本归属被拒
        JsonNode visas = om.readTree(mvc.perform(get("/api/visas").header("Authorization", "Bearer " + foreman4))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long basementVisa = -1;
        for (JsonNode v : visas) if ("QZ20261002001".equals(v.get("code").asText())) basementVisa = v.get("id").asLong();
        long newAddId = om.readTree(mvc.perform(post("/api/requisitions/additional")
                        .header("Authorization", "Bearer " + foreman4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "visaId", basementVisa, "crewId", 4,
                                "building", "1#楼", "area", "地下室", "process", "防水工程",
                                "drawingNo", "建防-201", "costBearing", "VISA_CLAIM",
                                "extraLaborQty", 2d, "extraLaborPrice", 200d,
                                "items", List.of(Map.of("materialId", 3, "applyQty", 50d))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeAdditional").value(true))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("id").asLong();
        assertEquals("SUBMITTED", om.readTree(mvc.perform(get("/api/requisitions/" + newAddId)
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("status").asText());
        mvc.perform(post("/api/requisitions/additional")
                        .header("Authorization", "Bearer " + foreman4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "visaId", basementVisa, "crewId", 4,
                                "building", "1#楼", "process", "防水工程", "costBearing", "UNKNOWN",
                                "items", List.of(Map.of("materialId", 3, "applyQty", 1d))))))
                .andExpect(status().isBadRequest());

        // 7. 追加领料上下文：平台关联原预算 180、已领 180、现场库存与已有追加单
        JsonNode ctx = om.readTree(mvc.perform(get("/api/visas")
                        .header("Authorization", "Bearer " + pm))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long toiletVisa = -1;
        for (JsonNode v : ctx) if ("QZ20261003002".equals(v.get("code").asText())) toiletVisa = v.get("id").asLong();
        JsonNode context = om.readTree(mvc.perform(get("/api/visas/" + toiletVisa + "/additional-context")
                        .header("Authorization", "Bearer " + pm))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(180d, context.get("budgets").get(0).get("budgetQty").asDouble(), 0.01);
        assertEquals(180d, context.get("issued").get(0).get("issuedQty").asDouble(), 0.01);
        assertTrue(context.get("additions").size() >= 1);

        // 8. 月底防水班（crewId=4）核算：合同内 / 签证 / 返工 成本三分
        JsonNode st4 = om.readTree(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("period", "2026-10", "crewId", 4))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // 合同内：卷材 (300+180)*32 + 安全网 30*45 - 完好退库 20*32 = 16070
        assertEquals(16070d, st4.get("contractCostAmount").asDouble(), 0.01);
        // 签证追加：120*32 卷材 + 8*200 人工 = 5440
        assertEquals(5440d, st4.get("visaCostAmount").asDouble(), 0.01);
        // 施工返工追加：30*32 + 2*200 = 1360，计入责任班组扣款
        assertEquals(1360d, st4.get("reworkCostAmount").asDouble(), 0.01);
        assertEquals(1360d, st4.get("deductionAmount").asDouble(), 0.01);
        // 签证索赔口径：6400(后浇带) + 5440(卫生间加高) + 0(返工不可签证)
        assertEquals(11840d, st4.get("claimAmount").asDouble(), 0.01);

        // 9. 财务总览：直接区分合同内成本、签证成本与班组返工成本
        JsonNode fin = om.readTree(mvc.perform(get("/api/analytics/finance")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(5440d, fin.get("visaBoundCostTotal").asDouble(), 0.01);
        assertEquals(1360d, fin.get("reworkBoundCostTotal").asDouble(), 0.01);
        boolean visaRowOk = false, reworkRowOk = false;
        for (JsonNode v : fin.get("visas")) {
            if ("QZ20261003002".equals(v.get("code").asText())) {
                assertEquals(5440d, v.get("boundMaterialCost").asDouble() + v.get("boundLaborCost").asDouble(), 0.01);
                assertEquals(5440d, v.get("visaClaimCost").asDouble(), 0.01);
                assertTrue(v.get("bound").asBoolean());
                visaRowOk = true;
            }
            if ("QZ20261003003".equals(v.get("code").asText())) {
                assertEquals(1360d, v.get("reworkCost").asDouble(), 0.01);
                assertEquals(0d, v.get("visaClaimCost").asDouble(), 0.01);
                reworkRowOk = true;
            }
        }
        assertTrue(visaRowOk && reworkRowOk, "财务签证台账应同时呈现签证成本与返工成本拆分");
    }
}

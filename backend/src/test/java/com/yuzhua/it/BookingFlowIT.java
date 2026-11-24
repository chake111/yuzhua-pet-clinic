package com.yuzhua.it;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户端下单 → 支付 → 取消的完整链路，以及鉴权失败时“HTTP 200 + body.code”的约定。
 */
class BookingFlowIT extends MySqlITSupport {

    @Test
    void bookingPayAndCancelFlow() throws Exception {
        String token = loginToken(mockMvc, "ituser");

        JsonNode created = createAppointment(mockMvc, token, FIRST_PET_ID, IT_DOCTOR_ID,
                IT_SCHEDULE_ID, "IT 测试");
        long appointmentId = created.path("data").path("id").asLong();
        assertTrue(appointmentId > 0, "下单未返回预约 ID");
        assertEquals(1, created.path("data").path("queueNo").asInt(), "首单排队序号应为 1");
        assertTrue(created.path("data").has("amount"), "下单响应缺 amount");
        assertEquals(80.00, created.path("data").path("amount").asDouble(), 0.001, "金额应来自医生挂号费");
        assertEquals(19, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "下单后号源应减一");
        assertEquals(1, queryInt("SELECT queue_no FROM appointment WHERE id = ?", appointmentId));

        // 第一次支付
        mockMvc.perform(post("/client/appointment/{id}/pay", appointmentId)
                        .header(authHeaderName(), bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        LocalDateTime firstPayTime = queryDateTime(
                "SELECT pay_time FROM appointment WHERE id = ?", appointmentId);
        assertNotNull(firstPayTime, "支付后应有 pay_time");
        assertEquals(1, queryInt("SELECT pay_status FROM appointment WHERE id = ?", appointmentId));
        assertEquals("MOCK", queryString("SELECT pay_type FROM appointment WHERE id = ?", appointmentId));

        // 第二次支付：幂等返回成功，pay_time 不被刷新
        mockMvc.perform(post("/client/appointment/{id}/pay", appointmentId)
                        .header(authHeaderName(), bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(firstPayTime,
                queryDateTime("SELECT pay_time FROM appointment WHERE id = ?", appointmentId),
                "重复支付不应重写 pay_time");
        assertEquals(1, queryInt("SELECT pay_status FROM appointment WHERE id = ?", appointmentId));

        // 第一次取消：退号，已支付标记退款
        mockMvc.perform(post("/client/appointment/{id}/cancel", appointmentId)
                        .header(authHeaderName(), bearer(token))
                        .contentType("application/json")
                        .content("{\"reason\":\"IT 测试取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第二次取消：业务异常仍是 HTTP 200，错误在 body.code
        mockMvc.perform(post("/client/appointment/{id}/cancel", appointmentId)
                        .header(authHeaderName(), bearer(token))
                        .contentType("application/json")
                        .content("{\"reason\":\"IT 测试重复取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("已取消")));

        assertEquals(3, queryInt("SELECT status FROM appointment WHERE id = ?", appointmentId));
        assertEquals(2, queryInt("SELECT pay_status FROM appointment WHERE id = ?", appointmentId));
        assertEquals(20, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "取消应把号源退回");
    }

    @Test
    void authFailuresUseBodyCodeWithHttp200() throws Exception {
        // 无 token 访问用户端受保护接口：HTTP 200 + body.code 401
        mockMvc.perform(get("/client/auth/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        // 后台 token 访问用户端接口：HTTP 200 + body.code 403
        String adminToken = loginAdminToken(mockMvc, "itadmin");
        mockMvc.perform(get("/client/auth/profile")
                        .header(authHeaderName(), bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
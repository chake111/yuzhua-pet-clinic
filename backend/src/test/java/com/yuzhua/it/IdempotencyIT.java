package com.yuzhua.it;

import tools.jackson.databind.JsonNode;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.service.AppointmentService;
import com.yuzhua.vo.AppointmentVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单幂等键：同键重放、并发同键、跨用户隔离、换时段与取消后的重放、非法键。
 * 关键不变量始终是"只落一单、只扣一次号"。
 */
class IdempotencyIT extends MySqlITSupport {

    private static final long SECOND_USER_ID = 9002L;
    private static final long SECOND_PET_ID = 900201L;
    private static final long SECOND_SCHEDULE_ID = 9002L;

    @Autowired
    private AppointmentService appointmentService;

    @Test
    void sameKeyReplayReturnsOriginalOrderAndOccupiesSlotOnce() throws Exception {
        String token = loginToken(mockMvc, "ituser");
        String first = createAppointment(mockMvc, token, FIRST_PET_ID, IT_DOCTOR_ID,
                IT_SCHEDULE_ID, "IT 幂等重放", "replay-key-0001")
                .path("data").path("id").asText();

        JsonNode second = createAppointment(mockMvc, token, FIRST_PET_ID, IT_DOCTOR_ID,
                IT_SCHEDULE_ID, "IT 幂等重放", "replay-key-0001");

        assertEquals(first, second.path("data").path("id").asText(), "同键重放应返回同一张单");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM appointment WHERE user_id = ? AND idem_key = ?",
                IT_USER_ID, "replay-key-0001"));
        assertEquals(19, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "重放不应再次扣号");
        assertEquals(1, queryInt("SELECT last_queue_no FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID));
        assertEquals("replay-key-0001",
                queryString("SELECT idem_key FROM appointment WHERE id = ?", Long.parseLong(first)));
    }

    @Test
    @Timeout(90)
    void twentyConcurrentSameKeyCreatesSingleOrder() throws Exception {
        String key = "concurrent-replay-01";
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Long> ids = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                UserContext.set(clientUser());
                try {
                    ready.countDown();
                    go.await();
                    AppointmentVo vo = appointmentService.create(appointmentForm(FIRST_PET_ID), key);
                    ids.add(vo.getId());
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    UserContext.clear();
                }
            }));
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "下单线程未全部就绪");
        go.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(failures.isEmpty(), () -> "并发同键下单出现异常: " + failures);
        assertEquals(threadCount, ids.size());
        assertEquals(1, new HashSet<>(ids).size(), "所有线程应返回同一张单: " + ids);
        assertEquals(1, queryInt("SELECT COUNT(*) FROM appointment WHERE user_id = ? AND idem_key = ?",
                IT_USER_ID, key));
        assertEquals(19, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "并发同键只应扣一次号");
        assertEquals(1, queryInt("SELECT last_queue_no FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID));
    }

    @Test
    void sameKeyIsScopedPerUser() {
        seedSecondUserAndPet();
        String key = "shared-key-0001";

        UserContext.set(clientUser());
        long first;
        try {
            first = appointmentService.create(appointmentForm(FIRST_PET_ID), key).getId();
        } finally {
            UserContext.clear();
        }

        UserContext.set(secondUser());
        long second;
        try {
            second = appointmentService.create(appointmentForm(SECOND_PET_ID), key).getId();
        } finally {
            UserContext.clear();
        }

        assertNotEquals(first, second, "唯一索引按用户维度，两个用户同键应各落一单");
        assertEquals(2, queryInt("SELECT COUNT(*) FROM appointment WHERE idem_key = ?", key));
        assertEquals(18, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "两张单应各扣一次号");
    }

    @Test
    void replayWithDifferentScheduleReturnsFirstOrderAndLeavesSecondSlotUntouched() {
        jdbc.update("INSERT INTO doctor_schedule (id, doctor_id, work_date, period, start_time, end_time, "
                        + "total_num, remain_num, last_queue_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                SECOND_SCHEDULE_ID, IT_DOCTOR_ID, java.time.LocalDate.now().plusDays(1), 1,
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(11, 30), 20, 20, 0);
        String key = "switch-schedule-01";

        UserContext.set(clientUser());
        long first;
        try {
            first = appointmentService.create(
                    appointmentForm(FIRST_PET_ID, IT_DOCTOR_ID, IT_SCHEDULE_ID, "IT 原时段"), key).getId();
        } finally {
            UserContext.clear();
        }

        UserContext.set(clientUser());
        long replay;
        try {
            replay = appointmentService.create(
                    appointmentForm(FIRST_PET_ID, IT_DOCTOR_ID, SECOND_SCHEDULE_ID, "IT 换时段"), key).getId();
        } finally {
            UserContext.clear();
        }

        assertEquals(first, replay, "换时段重放仍应返回首次落库的原单");
        assertEquals(20, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", SECOND_SCHEDULE_ID),
                "第二个时段不应被占用");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM appointment WHERE idem_key = ?", key));
    }

    @Test
    void replayAfterCancelReturnsCancelledOrderWithoutNewSlot() throws Exception {
        String token = loginToken(mockMvc, "ituser");
        String key = "cancel-then-replay-01";
        long id = createAppointment(mockMvc, token, FIRST_PET_ID, IT_DOCTOR_ID,
                IT_SCHEDULE_ID, "IT 取消后重放", key).path("data").path("id").asLong();

        mockMvc.perform(post("/client/appointment/{id}/cancel", id)
                        .header(authHeaderName(), bearer(token))
                        .contentType("application/json")
                        .content("{\"reason\":\"IT 取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(20, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID));

        JsonNode replay = createAppointment(mockMvc, token, FIRST_PET_ID, IT_DOCTOR_ID,
                IT_SCHEDULE_ID, "IT 取消后重放", key);

        assertEquals(id, replay.path("data").path("id").asLong());
        assertEquals(3, replay.path("data").path("status").asInt(), "重放应返回已取消的原单");
        assertEquals(20, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "重放不应新建单或再次占号");
        assertEquals(1, queryInt("SELECT COUNT(*) FROM appointment WHERE idem_key = ?", key));
    }

    @Test
    void malformedKeyIsRejectedBeforeAnyBooking() throws Exception {
        String token = loginToken(mockMvc, "ituser");

        for (String bad : List.of("bad key!", "short")) {
            mockMvc.perform(post("/client/appointment")
                            .header(authHeaderName(), bearer(token))
                            .header("Idempotency-Key", bad)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "petId", FIRST_PET_ID,
                                    "doctorId", IT_DOCTOR_ID,
                                    "scheduleId", IT_SCHEDULE_ID,
                                    "symptom", "IT 非法键"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.msg").value(containsString("幂等键")));
        }

        assertEquals(0, queryInt("SELECT COUNT(*) FROM appointment"));
        assertEquals(20, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID));
    }

    @Test
    void createWithoutKeyStillWorksAndLeavesKeyNull() {
        UserContext.set(clientUser());
        AppointmentVo vo;
        try {
            vo = appointmentService.create(appointmentForm(FIRST_PET_ID));
        } finally {
            UserContext.clear();
        }

        assertTrue(vo.getId() > 0);
        assertNull(jdbc.queryForObject("SELECT idem_key FROM appointment WHERE id = ?",
                        String.class, vo.getId()),
                "未带键的单 idem_key 应为 NULL");
        assertEquals(19, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID));
    }

    // ---------------------------------------------------------------- fixtures

    private void seedSecondUserAndPet() {
        jdbc.update("INSERT INTO sys_user (id, username, password, nickname, phone, status, token_version) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 1)",
                SECOND_USER_ID, "ituser2", SEED_PASSWORD_HASH, "IT用户2", "13900009002");
        jdbc.update("INSERT INTO pet (id, user_id, name, species, deleted) VALUES (?, ?, ?, ?, 0)",
                SECOND_PET_ID, SECOND_USER_ID, "IT宠物B", "狗");
    }

    private static LoginUser secondUser() {
        return new LoginUser(SECOND_USER_ID, "ituser2", "IT用户2", null, TokenType.CLIENT, UserRole.USER, null, 1, null);
    }
}
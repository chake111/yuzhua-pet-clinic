package com.yuzhua.it;

import tools.jackson.databind.JsonNode;
import com.yuzhua.security.UserContext;
import com.yuzhua.service.AppointmentService;
import com.yuzhua.vo.AppointmentVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一张单被 10 个线程同时支付：全部成功返回、只落一次支付时间。
 */
class DuplicatePayIT extends MySqlITSupport {

    @Autowired
    private AppointmentService appointmentService;

    @Test
    @Timeout(60)
    void tenConcurrentPaysAllSucceedWithSinglePayTime() throws Exception {
        JsonNode created = createAppointment(mockMvc, loginToken(mockMvc, "ituser"),
                FIRST_PET_ID, IT_DOCTOR_ID, IT_SCHEDULE_ID, "IT 重复支付");
        long appointmentId = created.path("data").path("id").asLong();
        assertTrue(appointmentId > 0);

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                UserContext.set(clientUser());
                try {
                    ready.countDown();
                    go.await();
                    AppointmentVo vo = appointmentService.pay(appointmentId);
                    assertNotNull(vo, "支付返回为空");
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    UserContext.clear();
                }
            }));
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "支付线程未全部就绪");
        go.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(failures.isEmpty(), () -> "并发支付出现异常: " + failures);
        assertEquals(1, queryInt("SELECT pay_status FROM appointment WHERE id = ?", appointmentId));
        assertEquals("MOCK", queryString("SELECT pay_type FROM appointment WHERE id = ?", appointmentId));
        assertEquals(1, queryInt("SELECT COUNT(DISTINCT pay_time) FROM appointment WHERE id = ?",
                appointmentId), "重复支付不应产生多个 pay_time");
    }
}
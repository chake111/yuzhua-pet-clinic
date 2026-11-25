package com.yuzhua.it;

import com.yuzhua.common.BizException;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务层并发抢号：100 个不同宠物同时抢同一排班的 20 个号，验证不超卖、不重复发号。
 */
class BookingRaceIT extends MySqlITSupport {

    @Autowired
    private AppointmentService appointmentService;

    @Test
    @Timeout(120)
    void exactlyTwentyOfHundredConcurrentBookingsSucceed() throws Exception {
        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            long petId = FIRST_PET_ID + i;
            futures.add(pool.submit(() -> {
                UserContext.set(new LoginUser(9001L, "ituser", "IT用户", null,
                        TokenType.CLIENT, UserRole.USER, null, 1, null));
                try {
                    ready.countDown();
                    go.await();
                    appointmentService.create(appointmentForm(petId));
                    return "OK";
                } catch (BizException e) {
                    return "BIZ:" + e.getMessage();
                } finally {
                    UserContext.clear();
                }
            }));
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS),
                "只有 " + (threadCount - ready.getCount()) + "/" + threadCount + " 个线程就绪");
        go.countDown();

        int ok = 0;
        int soldOut = 0;
        int other = 0;
        List<String> surprises = new ArrayList<>();
        for (Future<String> future : futures) {
            try {
                String outcome = future.get(60, TimeUnit.SECONDS);
                if ("OK".equals(outcome)) {
                    ok++;
                } else if (outcome.contains("号源已约满")) {
                    soldOut++;
                } else {
                    other++;
                    surprises.add(outcome);
                }
            } catch (ExecutionException e) {
                other++;
                surprises.add(String.valueOf(e.getCause()));
            }
        }
        pool.shutdown();

        assertEquals(20, ok, "成功下单数应恰好等于号源容量");
        assertEquals(80, soldOut, "其余请求应收到“号源已约满”业务异常");
        assertEquals(0, other, () -> "出现非预期异常/结果: " + surprises);
        assertEquals(0, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "余量应归零");
        assertEquals(20, queryInt("SELECT last_queue_no FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "已发放序号应为 20");
        assertEquals(20, queryInt("SELECT COUNT(DISTINCT queue_no) FROM appointment WHERE schedule_id = ?",
                IT_SCHEDULE_ID), "排队序号不应重复");
        assertEquals(1, queryInt("SELECT MIN(queue_no) FROM appointment WHERE schedule_id = ?", IT_SCHEDULE_ID));
        assertEquals(20, queryInt("SELECT MAX(queue_no) FROM appointment WHERE schedule_id = ?", IT_SCHEDULE_ID));
    }
}
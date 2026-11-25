package com.yuzhua.it;

import com.yuzhua.common.BizException;
import com.yuzhua.security.UserContext;
import com.yuzhua.service.AppointmentService;
import com.yuzhua.vo.AppointmentVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付超时清扫与用户取消的相容性：清扫幂等、退号只退一次、并发下两者只有一个胜出。
 */
class ExpireAndRaceIT extends MySqlITSupport {

    @Autowired
    private AppointmentService appointmentService;

    @Test
    void expireOneCancelsOnceAndReleasesSlotOnlyOnce() {
        long id = createOrder(FIRST_PET_ID);
        backdate(id);

        assertTrue(appointmentService.expireOne(id), "第一次清扫应取消成功");
        assertFalse(appointmentService.expireOne(id), "同一张单第二次清扫应返回 false");

        assertEquals(3, queryInt("SELECT status FROM appointment WHERE id = ?", id));
        assertEquals(0, queryInt("SELECT pay_status FROM appointment WHERE id = ?", id));
        assertEquals(20, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "超时取消应把号源退回且只退一次");
        assertEquals("SYSTEM", queryString("SELECT cancel_by FROM appointment WHERE id = ?", id));
    }

    @Test
    void payRejectsExpiredOrderAndLeavesItPending() {
        long id = createOrder(FIRST_PET_ID + 1);
        backdate(id);

        UserContext.set(clientUser());
        try {
            BizException e = assertThrows(BizException.class, () -> appointmentService.pay(id));
            assertTrue(e.getMessage().contains("超过"), "异常消息应说明支付超时: " + e.getMessage());
        } finally {
            UserContext.clear();
        }
        assertEquals(0, queryInt("SELECT status FROM appointment WHERE id = ?", id),
                "支付失败不应改变订单状态");
        assertEquals(0, queryInt("SELECT pay_status FROM appointment WHERE id = ?", id));
    }

    @Test
    @Timeout(60)
    void concurrentCancelAndExpireOnlyOneWins() throws Exception {
        long id = createOrder(FIRST_PET_ID + 2);
        backdate(id);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<Boolean> cancel = pool.submit(() -> {
            UserContext.set(clientUser());
            try {
                ready.countDown();
                go.await();
                appointmentService.cancelByUser(id, "IT 并发取消");
                return true;
            } catch (BizException e) {
                // VisitOverdueException 继承 BizException，过期未支付单也走这里
                return false;
            } finally {
                UserContext.clear();
            }
        });
        Future<Boolean> expire = pool.submit(() -> {
            ready.countDown();
            go.await();
            return appointmentService.expireOne(id);
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "两个线程未就绪");
        go.countDown();
        boolean cancelled = cancel.get(30, TimeUnit.SECONDS);
        boolean expired = expire.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (cancelled ? 1 : 0) + (expired ? 1 : 0),
                "用户取消与超时清扫只能有一个成功");
        assertEquals(3, queryInt("SELECT status FROM appointment WHERE id = ?", id));
        assertEquals(0, queryInt("SELECT pay_status FROM appointment WHERE id = ?", id));
        assertEquals(20, queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID),
                "号源只能退回一次，remain_num 不能变成 21");
    }

    /** 走服务层建单，UserContext 按用户端身份设置 */
    private long createOrder(long petId) {
        UserContext.set(clientUser());
        try {
            AppointmentVo vo = appointmentService.create(appointmentForm(petId));
            return Objects.requireNonNull(vo.id());
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 把 create_time 回拨到超时线之外。用 JVM 的 LocalDateTime 作为绑定参数，
     * 不依赖容器的 NOW()（容器默认 UTC，JVM 是 Asia/Shanghai）。
     */
    private void backdate(long appointmentId) {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(31);
        jdbc.update("UPDATE appointment SET create_time = ?, update_time = ? WHERE id = ?",
                stale, stale, appointmentId);
    }
}
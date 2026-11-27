package com.yuzhua.it;

import com.yuzhua.common.BizException;
import com.yuzhua.security.UserContext;
import com.yuzhua.service.AppointmentService;
import com.yuzhua.vo.AppointmentVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一张待支付单被 pay 与 cancelByUser 同时争抢。
 *
 * <p>两个入口都先锁预约行，所以只有一条路径能先进入临界区：
 * <ul>
 *   <li>取消先赢：状态改 3、号源退回 20，随后 pay 看到"非待接诊"报业务异常；</li>
 *   <li>支付先赢：pay 提交后 cancel 拿到锁，读到"待接诊 + 已支付"，按既定口径取消并退款，
 *       最终仍是 3/已退款/20（两个调用都成功）。取消接口允许取消已支付待接诊单是既有业务语义，
 *       所以这里不存在"已支付 + 待接诊 + remain 19"的终态——那意味着 cancel 线程没有真正执行。</li>
 * </ul>
 * 无论谁先赢，终态都必须是"已取消 + 号源只退一次"，且绝不能出现取消后 pay_status 仍为已支付。
 */
class PayCancelRaceIT extends MySqlITSupport {

    @Autowired
    private AppointmentService appointmentService;

    @Test
    @Timeout(60)
    void concurrentPayAndCancelLeaveConsistentFinalState() throws Exception {
        long id = createPendingOrder(FIRST_PET_ID);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<Boolean> payResult = pool.submit(() -> {
            UserContext.set(clientUser());
            try {
                ready.countDown();
                go.await();
                appointmentService.pay(id);
                return true;
            } catch (BizException e) {
                // 取消先赢时支付必报业务异常（状态已不是待接诊），这是预期分支
                return false;
            } finally {
                UserContext.clear();
            }
        });

        Future<Boolean> cancelResult = pool.submit(() -> {
            UserContext.set(clientUser());
            try {
                ready.countDown();
                go.await();
                appointmentService.cancelByUser(id, "IT 并发取消");
                return true;
            } catch (BizException e) {
                // 取消接口允许取消已支付待接诊单，正常不应走到这里；返回 false 让断言暴露
                return false;
            } finally {
                UserContext.clear();
            }
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "两个线程未就绪");
        go.countDown();
        boolean paid = payResult.get(30, TimeUnit.SECONDS);
        boolean cancelled = cancelResult.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(cancelled, "两个线程都结束后取消应成功（支付先赢时走退款）");

        int status = queryInt("SELECT status FROM appointment WHERE id = ?", id);
        int payStatus = queryInt("SELECT pay_status FROM appointment WHERE id = ?", id);
        int remain = queryInt("SELECT remain_num FROM doctor_schedule WHERE id = ?", IT_SCHEDULE_ID);

        assertEquals(3, status, "终态必须是已取消");
        assertEquals(20, remain, "号源只能退回一次，不能是 19 或 21");
        assertFalse(payStatus == 1, "取消后的单不能停留在已支付");
        assertEquals(0, queryInt("SELECT COUNT(*) FROM appointment "
                + "WHERE id = ? AND status = 0 AND pay_status = 1", id),
                "不能出现 remain 已退回却仍挂在待接诊已支付的单");

        if (paid) {
            assertEquals(2, payStatus, "支付先赢、随后取消，支付状态应为已退款");
            assertNotNull(queryDateTime("SELECT pay_time FROM appointment WHERE id = ?", id),
                    "确实支付过就应有 pay_time");
        } else {
            assertEquals(0, payStatus, "取消先赢时不应留下支付/退款痕迹");
            assertNull(queryDateTime("SELECT pay_time FROM appointment WHERE id = ?", id),
                    "取消先赢时不应写 pay_time");
        }

        assertTrue(queryInt("SELECT COUNT(DISTINCT pay_time) FROM appointment WHERE id = ?", id) <= 1,
                "同一张单只应有一个支付时间");
    }

    /** 走服务层建一张待支付单，UserContext 按用户端身份设置 */
    private long createPendingOrder(long petId) {
        UserContext.set(clientUser());
        try {
            AppointmentVo vo = appointmentService.create(appointmentForm(petId));
            return Objects.requireNonNull(vo.id());
        } finally {
            UserContext.clear();
        }
    }
}
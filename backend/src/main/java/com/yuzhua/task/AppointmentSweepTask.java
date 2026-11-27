package com.yuzhua.task;

import com.yuzhua.config.AppProperties;
import com.yuzhua.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.function.Predicate;

/**
 * 预约状态定时清扫任务：每轮执行前先竞争 {@link SchedulerLease} 数据库租约，
 * 按单条独立事务清扫，防止整批共事务与用户取消锁顺序相反引发死锁 1213。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentSweepTask {

    private final AppointmentService appointmentService;
    private final SchedulerLease schedulerLease;
    private final AppProperties props;

    /** 按固定延迟清扫超时未支付订单并释放号源；单笔失败不影响同批后续订单。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expireUnpaid() {
        withLease("appointment.expire-unpaid", () ->
                sweep("未支付超时", appointmentService.unpaidOverdueIds(), appointmentService::expireOne));
    }

    /** 每日 00:30 及应用就绪后将过就诊日仍待接诊的已支付订单标为未到诊（不退号不退款）。 */
    @Scheduled(cron = "0 30 0 * * ?")
    @EventListener(ApplicationReadyEvent.class)
    public void markMissed() {
        withLease("appointment.mark-missed", () ->
                sweep("未到诊标记", appointmentService.staleVisitIds(), appointmentService::markMissedOne));
    }

    private void withLease(String name, Runnable action) {
        long ttlSeconds = props.getScheduler().getLeaseSeconds();
        if (!schedulerLease.tryAcquire(name, ttlSeconds)) {
            log.debug("定时任务租约被其他实例持有，本轮跳过：{}", name);
            return;
        }
        try {
            // 原因: sweep 内部逐单捕获异常继续后续订单，仅候选查询异常会冒泡并在 finally 释放租约
            action.run();
        } finally {
            // 原因: 无论成功或失败均提前释放租约，避免其余实例等待至 TTL 到期
            schedulerLease.release(name);
        }
    }

    private void sweep(String what, List<Long> ids, Predicate<Long> perOrder) {
        if (ids.isEmpty()) {
            return;
        }
        int done = 0;
        for (Long id : ids) {
            try {
                if (perOrder.test(id)) {
                    done++;
                }
            } catch (Exception e) {
                log.error("{}：处理预约 {} 失败，留给下一轮", what, id, e);
            }
        }
        if (done > 0) {
            log.info("{}：候选 {} 单，本次处理 {} 单", what, ids.size(), done);
        }
    }
}

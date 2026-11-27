package com.yuzhua.task;

import com.yuzhua.mapper.SchedulerLeaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * 定时任务多实例数据库租约：基于数据库时钟 NOW(3) 与原子写实现互斥，防止多实例时钟漂移抢占；
 * 仅控制单轮执行权，订单并发正确性仍由行锁与条件更新保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerLease {

    private static final int HOLDER_RANDOM_PREFIX_LEN = 8;
    private static final int MAX_HOLDER_LENGTH = 128;

    private final SchedulerLeaseMapper leaseMapper;

    private final String holder = buildHolder();

    public boolean tryAcquire(String name, long ttlSeconds) {
        if (leaseMapper.insertIfAbsent(name, holder, ttlSeconds) == 1) {
            log.debug("获得定时任务租约：{} holder={}", name, holder);
            return true;
        }
        if (leaseMapper.takeOverExpired(name, holder, ttlSeconds) == 1) {
            log.debug("接管过期定时任务租约：{} holder={}", name, holder);
            return true;
        }
        log.debug("定时任务租约由其他实例持有，本轮跳过：{} holder={}", name, holder);
        return false;
    }

    public void release(String name) {
        if (leaseMapper.release(name, holder) == 0) {
            log.debug("租约已被接管，忽略释放：{} holder={}", name, holder);
        }
    }

    private static String buildHolder() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        String raw = host + "/" + ProcessHandle.current().pid() + "/"
                + UUID.randomUUID().toString().substring(0, HOLDER_RANDOM_PREFIX_LEN);
        // 原因: holder 列宽 128，超长时从头部截断并保留末尾 128 字符（优先保住尾部随机后缀），防库表截断尾部导致释放失配
        return raw.length() <= MAX_HOLDER_LENGTH ? raw : raw.substring(raw.length() - MAX_HOLDER_LENGTH);
    }
}
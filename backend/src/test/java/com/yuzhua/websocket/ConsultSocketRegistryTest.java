package com.yuzhua.websocket;

import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.security.LoginUser;
import com.yuzhua.websocket.ConsultSocketRegistry.Conn;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 连接注销：关闭回调只能注销"自己那条"，不能把同一账号刚登记的新连接删掉。
 *
 * <p>旧实现是"先 get 比较、再 remove(key)"，两步之间新连接完成 put 就会被误删，
 * 表现为握手成功、READY 收到，之后收不到任何广播，医生在线状态也跟着判错。
 */
class ConsultSocketRegistryTest {

    private static LoginUser user() {
        LoginUser u = new LoginUser();
        u.setId(1L);
        u.setTyp(TokenType.CLIENT);
        return u;
    }

    @Test
    void closingStaleConnectionKeepsNewRegistration() {
        ConsultSocketRegistry registry = new ConsultSocketRegistry();
        Conn old = new Conn(ConsultSocketRegistry.keyOf(user()), user(), null);
        Conn fresh = new Conn(ConsultSocketRegistry.keyOf(user()), user(), null);

        registry.open(old);
        assertSame(old, registry.open(fresh), "顶号时应返回被替换的旧连接");

        // 旧连接的关闭回调晚到：它的注销请求不能生效
        assertNull(registry.close(old.getKey(), old));
        assertSame(fresh, registry.find(old.getKey()), "新连接必须仍在登记表中");

        // 只有新连接自己的关闭才会真正注销
        assertSame(fresh, registry.close(fresh.getKey(), fresh));
        assertNull(registry.find(fresh.getKey()));
    }

    @Test
    void closingTwiceIsIdempotent() {
        ConsultSocketRegistry registry = new ConsultSocketRegistry();
        Conn conn = new Conn(ConsultSocketRegistry.keyOf(user()), user(), null);
        registry.open(conn);
        assertSame(conn, registry.close(conn.getKey(), conn));
        assertNull(registry.close(conn.getKey(), conn), "重复关闭不应返回连接，否则会重复广播离线");
    }

    /**
     * 并发压力检查：两线程在同一道闸门后同时起跑，重复 300 轮，最终登记表里必须是新连接。
     *
     * <p>闸门只保证"同时起跑"，并不固定"读旧连接 → 登记新连接 → 删除"这一具体交错；
     * 这 300 轮能覆盖到多种调度顺序，但命中旧实现那条窗口并不保证。
     * 真正的确定性保证来自 {@code remove(key, value)}：比较与删除是一个原子操作，
     * 任何交错下都不会把新登记删掉。
     */
    @Test
    void concurrentOpenAndCloseKeepsTheNewConnectionUnderStress() throws Exception {
        for (int round = 0; round < 300; round++) {
            ConsultSocketRegistry registry = new ConsultSocketRegistry();
            LoginUser user = user();
            String key = ConsultSocketRegistry.keyOf(user);
            Conn old = new Conn(key, user, null);
            Conn fresh = new Conn(key, user, null);
            registry.open(old);

            CyclicBarrier gate = new CyclicBarrier(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread closer = new Thread(() -> run(gate, failure, () -> registry.close(key, old)));
            Thread opener = new Thread(() -> run(gate, failure, () -> registry.open(fresh)));
            closer.start();
            opener.start();
            closer.join();
            opener.join();

            assertNull(failure.get(), "第 " + round + " 轮线程异常：" + failure.get());
            assertSame(fresh, registry.find(key), "第 " + round + " 轮：新连接被旧连接的注销删掉了");
        }
    }

    private static void run(CyclicBarrier gate, AtomicReference<Throwable> failure, Runnable action) {
        try {
            gate.await();
            action.run();
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }
}

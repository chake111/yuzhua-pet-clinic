package com.yuzhua.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhua.websocket.ConsultDeliveryGuard.Decision;
import com.yuzhua.websocket.ConsultSocketRegistry.Conn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.util.List;
import java.util.Map;

/**
 * 统一的出站发送入口。REST 侧（接诊/结束）与 WebSocket 处理器都从这里写帧。
 *
 * 契约动机: 避免广播未提交或回滚的状态——对端收到 SESSION 帧会立刻回查会话，提交前推送
 * 会让它读到尚未提交的旧状态，回滚后广播更是谎报"已结束"。所以有事务时注册 afterCommit，
 * 提交成功才真正发送；单个连接写失败只记 debug，通知失败不能推翻已完成的业务操作。
 *
 * 每条帧写出前都经过 {@link ConsultDeliveryGuard}：账号失效直接关闭连接（不先回错误帧，
 * 否则"写错误帧 → 校验失败 → 再写错误帧"会递归）；会话准入失败只丢当前帧并清退该房间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsultPush {

    private final ConsultSocketRegistry registry;
    private final ConsultDeliveryGuard guard;
    private final ObjectMapper objectMapper;

    /** 业务广播：事务提交后向会话房间内的当事人发送 */
    public void push(Long sessionId, Map<String, Object> payload) {
        afterCommit(() -> sendNow(sessionId, payload));
    }

    /**
     * 处置通知：事务提交后先向原订阅连接发送 MODERATION，随后无条件清退该房间订阅。
     * 清退放在 finally，单条连接发送异常也要完成，否则处置后的正文仍可能被投递。
     */
    public void pushModeration(Long sessionId, Map<String, Object> payload) {
        afterCommit(() -> {
            List<Conn> targets = registry.inRoom(sessionId);
            try {
                for (Conn c : targets) {
                    deliver(c, sessionId, payload, false);
                }
            } finally {
                for (Conn c : targets) {
                    registry.leaveRoom(c, sessionId);
                }
            }
        });
    }

    /** 单条连接的普通业务帧（Handler 的 HISTORY / MSG / ACK / READ / PRESENCE） */
    public void send(Conn conn, Long sessionId, Map<String, Object> payload) {
        if (sessionId == null) {
            sendControl(conn, payload);
            return;
        }
        deliver(conn, sessionId, payload, true);
    }

    /** 单条连接的控制帧（READY / PONG / 普通 ERROR）：只核对账号与令牌 */
    public void sendControl(Conn conn, Map<String, Object> payload) {
        if (conn.getWs() == null) {
            return;
        }
        if (guard.checkAccount(conn.getUser()) == Decision.ACCOUNT_INVALID) {
            closeForAccount(conn);
            return;
        }
        write(conn, payload);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void sendNow(Long sessionId, Map<String, Object> payload) {
        for (Conn c : registry.inRoom(sessionId)) {
            deliver(c, sessionId, payload, true);
        }
    }

    private void deliver(Conn conn, Long sessionId, Map<String, Object> payload, boolean requireNormalModeration) {
        if (conn.getWs() == null) {
            return;
        }
        Decision decision = requireNormalModeration
                ? guard.checkBusiness(conn.getUser(), sessionId)
                : guard.checkParticipant(conn.getUser(), sessionId);
        if (decision == Decision.ACCOUNT_INVALID) {
            closeForAccount(conn);
            return;
        }
        if (decision == Decision.SESSION_DENIED) {
            registry.leaveRoom(conn, sessionId);
            return;
        }
        write(conn, payload);
    }

    private void write(Conn conn, Map<String, Object> payload) {
        try {
            conn.getWs().sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("会话帧下发失败 key={} err={}", conn.getKey(), e.getMessage());
        }
    }

    private void closeForAccount(Conn conn) {
        try {
            conn.getWs().close(CloseStatus.POLICY_VIOLATION.withReason("account disabled"));
        } catch (Exception e) {
            log.debug("关闭失效账号连接失败 key={} err={}", conn.getKey(), e.getMessage());
        }
    }
}

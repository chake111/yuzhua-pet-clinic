package com.yuzhua.websocket;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.yuzhua.common.BizException;
import com.yuzhua.entity.ConsultMessage;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.AccountGuard;
import com.yuzhua.security.UserContext;
import com.yuzhua.security.WsHandshakeInterceptor;
import com.yuzhua.service.ConsultService;
import com.yuzhua.websocket.ConsultSocketRegistry.Conn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 图文问诊的 WebSocket 端点。
 *
 * 协议是四类上行 / 五类下行，都用 {type: ...} 的 JSON 信封：
 *   上行 SUBSCRIBE / CHAT / READ / PING
 *   下行 READY / HISTORY / MSG / READ / PRESENCE / ERROR / PONG
 * 消息一律先落库再推送：连接只是投递通道，历史以库为准，
 * 所以断线重连后前端重新 SUBSCRIBE 就能补齐，不依赖推送不丢。
 * SUBSCRIBE 只补历史，不写已读；已读只认显式 READ 帧（见 subscribe / read 的注释）。
 *
 * 握手线程和这里都不是 MVC 的 HandlerInterceptor 管辖范围，
 * 所以进入业务方法前手动把登录态塞进 UserContext、出来时清掉——
 * 这是这套 ThreadLocal 方案唯一需要额外小心的地方。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsultWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_CONN = "wsConn";

    private final ConsultService consultService;
    private final ConsultSocketRegistry registry;
    private final ConsultPush consultPush;
    private final ObjectMapper objectMapper;
    private final AccountGuard accountGuard;

    /** 建连登记（同账号顶替旧连接）、标记医生上线并下发 READY；若握手携带 sessionId 则自动订阅。 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        LoginUser user = user(session);
        // 原因: 同账号可能同时收到广播与回执写帧，用串行化包装避免 WebSocket 并发写异常
        Conn conn = new Conn(ConsultSocketRegistry.keyOf(user), user,
                new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024));
        session.getAttributes().put(ATTR_CONN, conn);

        Conn stale = registry.open(conn);
        if (stale != null) {
            closeQuietly(stale);
        }
        runAs(user, () -> {
            Long doctorId = user.getDoctorId();
            if (user.isDoctor() && doctorId != null) {
                consultService.markDoctorOnline(doctorId, true);
            }
            writeControl(conn, envelope("READY", Map.of("name", String.valueOf(user.getName()))));
            Object sid = session.getAttributes().get(WsHandshakeInterceptor.ATTR_SESSION_ID);
            if (sid instanceof Long id) {
                subscribe(conn, id);
            }
        });
    }

    /** 入站文本帧总入口：先经 AccountGuard 核验账号状态，再按 type 分发并维持 UserContext 边界。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Conn conn = conn(session);
        if (conn == null) {
            return;
        }
        if (!accountGuard.active(conn.getUser())) {
            err(conn, "账号状态或权限已变化，请重新登录");
            WebSocketSession ws = conn.getWs();
            if (ws != null) {
                try {
                    ws.close(CloseStatus.POLICY_VIOLATION.withReason("account disabled"));
                } catch (IOException e) {
                    log.debug("关闭停用账号连接失败 key={}", conn.getKey());
                }
            }
            return;
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(message.getPayload(), new TypeReference<>() {
            });
        } catch (Exception e) {
            writeControl(conn, envelope("ERROR", Map.of("msg", "消息不是合法 JSON")));
            return;
        }
        String type = str(payload.get("type"));
        Long sessionId = asLong(payload.get("sessionId"));
        runAs(conn.getUser(), () -> {
            switch (type == null ? "" : type.toUpperCase()) {
                case "SUBSCRIBE" -> subscribe(conn, sessionId);
                case "CHAT" -> {
                    String requestId = str(payload.get("requestId"));
                    try {
                        chat(conn, sessionId, str(payload.get("msgType")), str(payload.get("content")), requestId);
                    } catch (BizException e) {
                        chatFailed(conn, e.getMessage(), requestId);
                    } catch (RuntimeException e) {
                        // 原因: 非预期异常同样须按 requestId 回执，防止前端一直等到发送超时
                        log.error("问诊消息保存失败 key={}", conn.getKey(), e);
                        chatFailed(conn, "消息未保存，请稍后重试", requestId);
                    }
                }
                case "READ" -> read(conn, sessionId);
                case "PING" -> writeControl(conn, envelope("PONG", Map.of()));
                default -> err(conn, "不支持的消息类型：" + type);
            }
        });
    }

    /** 原子条件注销当前连接；仅当该医生无其余活动连接时才广播离线并更新在线状态。 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Conn conn = conn(session);
        if (conn == null) {
            return;
        }
        // 原因: 顶号时旧连接晚到关闭回调，条件删除避免误删刚登记的新连接或误改医生在线态
        if (registry.close(conn.getKey(), conn) == null) {
            return;
        }
        LoginUser user = conn.getUser();
        if (!user.isDoctor()) {
            return;
        }
        for (Long roomId : conn.getRooms()) {
            broadcast(roomId, envelope("PRESENCE", Map.of("sessionId", roomId, "doctorOnline", false)), conn.getKey());
        }
        Long doctorId = user.getDoctorId();
        if (doctorId != null && !registry.doctorConnected(doctorId)) {
            consultService.markDoctorOnline(doctorId, false);
        }
    }

    private void subscribe(Conn conn, @Nullable Long sessionId) {
        if (sessionId == null) {
            err(conn, "缺少 sessionId");
            return;
        }
        join(conn, sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("messages", consultService.recent(sessionId, 20));
        write(conn, sessionId, envelope("HISTORY", data));
        write(conn, sessionId, envelope("PRESENCE", Map.of("sessionId", sessionId, "doctorOnline",
                registry.inRoom(sessionId).stream().anyMatch(c -> c.getUser().isDoctor()))));
    }

    private void chat(Conn conn, @Nullable Long sessionId, @Nullable String msgType,
                      @Nullable String content, @Nullable String requestId) {
        if (sessionId == null) {
            err(conn, "缺少 sessionId");
            return;
        }
        // 原因: 未进房时自动 join 先做参与方权限校验，不信任客户端房间状态
        join(conn, sessionId);
        // 原因: 未读计数不看对方是否在线，避免推送丢帧后红点永久漏记
        ConsultMessage msg = consultService.send(sessionId, msgType, content);
        broadcast(sessionId, envelope("MSG", Map.of("message", msg)), null);
        if (requestId != null) write(conn, sessionId, envelope("ACK", Map.of("sessionId", sessionId, "requestId", requestId, "message", msg)));
    }

    private void read(Conn conn, @Nullable Long sessionId) {
        if (sessionId == null) {
            err(conn, "缺少 sessionId");
            return;
        }
        join(conn, sessionId);
        List<Long> ids = consultService.markRead(sessionId);
        broadcastRead(conn, sessionId, ids);
    }

    private void broadcastRead(Conn conn, Long sessionId, List<Long> ids) {
        String role = conn.getUser().isDoctor() ? ConsultMessage.ROLE_DOCTOR : ConsultMessage.ROLE_USER;
        if (ids.isEmpty()) {
            broadcast(sessionId, envelope("READ", Map.of(
                    "sessionId", sessionId, "role", role, "readMessageIds", List.of())), conn.getKey());
            return;
        }
        for (int from = 0; from < ids.size(); from += 200) {
            List<Long> batch = ids.subList(from, Math.min(from + 200, ids.size()));
            broadcast(sessionId, envelope("READ", Map.of(
                    "sessionId", sessionId, "role", role, "readMessageIds", batch)), conn.getKey());
        }
    }

    private void join(Conn conn, Long sessionId) {
        consultService.requireMember(sessionId);
        if (conn.getRooms().contains(sessionId)) {
            return;
        }
        registry.join(conn, sessionId);
        if (conn.getUser().isDoctor()) {
            broadcast(sessionId, envelope("PRESENCE", Map.of("sessionId", sessionId, "doctorOnline", true)), conn.getKey());
        }
    }

    private void broadcast(Long roomId, Map<String, Object> payload, @Nullable String exceptKey) {
        List<Conn> targets = registry.inRoom(roomId);
        for (Conn c : targets) {
            if (exceptKey == null || !exceptKey.equals(c.getKey())) {
                write(c, roomId, payload);
            }
        }
    }

    private void write(Conn conn, Long sessionId, Map<String, Object> payload) {
        consultPush.send(conn, sessionId, payload);
    }

    private void writeControl(Conn conn, Map<String, Object> payload) {
        consultPush.sendControl(conn, payload);
    }

    private void err(Conn conn, @Nullable String msg) {
        writeControl(conn, envelope("ERROR", Map.of("msg", msg == null || msg.isBlank() ? "服务处理失败，请稍后重试" : msg)));
    }

    private void chatFailed(Conn conn, @Nullable String msg, @Nullable String requestId) {
        Map<String, Object> data = new HashMap<>();
        data.put("msg", msg == null || msg.isBlank() ? "消息未保存，请稍后重试" : msg);
        data.put("requestId", requestId == null ? "" : requestId);
        writeControl(conn, envelope("ERROR", data));
    }

    private Map<String, Object> envelope(String type, Map<String, Object> data) {
        Map<String, Object> out = new HashMap<>(data);
        out.put("type", type);
        return out;
    }

    private void closeQuietly(Conn conn) {
        WebSocketSession ws = conn.getWs();
        if (ws == null) {
            return;
        }
        try {
            ws.close(CloseStatus.NORMAL.withReason("replaced"));
        } catch (IOException e) {
            log.debug("关闭被顶掉的连接失败 key={}", conn.getKey());
        }
    }

    private void runAs(LoginUser user, Runnable body) {
        UserContext.set(user);
        try {
            body.run();
        } catch (BizException e) {
            err(connOf(user), e.getMessage());
        } catch (Exception e) {
            log.error("WebSocket 处理异常 key={}", user == null ? "?" : user.getId(), e);
            err(connOf(user), "服务处理失败，请稍后重试");
        } finally {
            UserContext.clear();
        }
    }

    private Conn connOf(LoginUser user) {
        Conn cur = registry.find(ConsultSocketRegistry.keyOf(user));
        return cur == null ? new Conn(ConsultSocketRegistry.keyOf(user), user, null) : cur;
    }

    private static LoginUser user(WebSocketSession session) {
        return Objects.requireNonNull((LoginUser) session.getAttributes().get(WsHandshakeInterceptor.ATTR_USER));
    }

    private static @Nullable Conn conn(WebSocketSession session) {
        Object v = session.getAttributes().get(ATTR_CONN);
        return v instanceof Conn c ? c : null;
    }

    private static @Nullable String str(@Nullable Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static @Nullable Long asLong(@Nullable Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank() && s.chars().allMatch(Character::isDigit)) {
            return Long.valueOf(s);
        }
        return null;
    }
}

package com.yuzhua.websocket;

import com.yuzhua.consult.ws.DoctorPresenceGateway;
import com.yuzhua.security.LoginUser;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线连接登记表。
 *
 * 刻意做成内存结构而不是"会话粘在某个节点上"：单店单机部署，
 * 没有水平扩容需求，所以不需要 Redis 发布订阅来转发消息。
 * 一旦要上多实例，把这个类抽成接口、换一种实现即可，协议层不用动。
 *
 * 一个账号只保留一条连接：同一账号重复连接时后来者顶掉前者
 * （前端刷新页面就会重连，两条连接会让消息重复推送）。
 */
@Component
public class ConsultSocketRegistry implements DoctorPresenceGateway {

    private final Map<String, Conn> conns = new ConcurrentHashMap<>();

    @Getter
    @RequiredArgsConstructor
    public static class Conn {
        private final String key;
        private final LoginUser user;
        private final @Nullable WebSocketSession ws;
        private final Set<Long> rooms = ConcurrentHashMap.newKeySet();
    }

    /** 生成账号维度的唯一连接键（体系:主键）。 */
    public static String keyOf(LoginUser u) {
        return u.getTyp() + ":" + u.getId();
    }

    /** 登记新连接，返回被顶掉的同账号旧连接（可能为 null） */
    public @Nullable Conn open(Conn conn) {
        return conns.put(conn.getKey(), conn);
    }

    /**
     * 注销。只有当前登记的确实是要关的那条才生效。
     *
     * <p>必须用 {@code remove(key, value)} 的条件删除，不能"先 get 比较、再 remove(key)"：
     * 两步之间若新连接完成登记，后一步会把新连接删掉，而新连接自己毫无察觉——
     * 表现是握手成功、READY 也到了，之后却收不到任何广播，医生在线状态也会判错。
     * 条件删除把比较与删除合成一个原子操作。
     */
    public @Nullable Conn close(String key, Conn conn) {
        return conns.remove(key, conn) ? conn : null;
    }

    /** 按连接键查询当前有效连接，不存在时返回 null。 */
    public @Nullable Conn find(String key) {
        return conns.get(key);
    }

    /** 将指定连接加入会话房间订阅集合。 */
    public void join(Conn conn, Long roomId) {
        conn.getRooms().add(roomId);
    }

    /**
     * 清退单条连接的一个房间订阅，连接本身与其他房间保留。
     * 会话被处置后旧订阅不能再收到正文；同一个账号其他合法会话不受影响。
     */
    public boolean leaveRoom(Conn conn, Long roomId) {
        return conn.getRooms().remove(roomId);
    }

    /** 返回当前订阅了指定会话房间的连接快照列表。 */
    public List<Conn> inRoom(Long roomId) {
        List<Conn> out = new ArrayList<>();
        for (Conn c : conns.values()) {
            if (c.getRooms().contains(roomId)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 这位医生还挂着活动连接吗（多标签页/顶号时不能误判成离线） */
    @Override
    public boolean doctorConnected(Long doctorId) {
        return conns.values().stream().anyMatch(c ->
                c.getUser().isDoctor() && doctorId.equals(c.getUser().getDoctorId()));
    }
}

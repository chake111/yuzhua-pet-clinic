package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.ConsultSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

public interface ConsultSessionMapper extends BaseMapper<ConsultSession> {

    /**
     * 锁定一条会话行。问诊的写操作（发送/接诊/结束/已读/处置）都必须先拿到这把锁，
     * 锁内再读状态、写消息、更摘要，保证同一会话内的提交顺序与摘要顺序一致。
     * 必须在事务内调用。
     */
    @Select("SELECT * FROM consult_session WHERE id = #{sessionId} FOR UPDATE")
    @Nullable ConsultSession selectByIdForUpdate(@Param("sessionId") Long sessionId);

    // 原因: countUnread 仅区分不涨未读的系统消息，参与方消息一律累加并由 markRead 同锁清零
    @Update("""
            UPDATE consult_session SET
              last_message = #{preview},
              last_message_time = NOW(),
              user_unread   = user_unread   + IF(#{byDoctor} AND #{countUnread}, 1, 0),
              doctor_unread = doctor_unread + IF(NOT #{byDoctor} AND #{countUnread}, 1, 0),
              update_time = NOW()
            WHERE id = #{sessionId}
            """)
    int touchOnMessage(@Param("sessionId") Long sessionId,
                       @Param("preview") String preview,
                       @Param("byDoctor") boolean byDoctor,
                       @Param("countUnread") boolean countUnread);

    @Update("UPDATE consult_session SET user_unread = 0, update_time = NOW() WHERE id = #{sessionId}")
    int clearUserUnread(@Param("sessionId") Long sessionId);

    @Update("UPDATE consult_session SET doctor_unread = 0, update_time = NOW() WHERE id = #{sessionId}")
    int clearDoctorUnread(@Param("sessionId") Long sessionId);

    // 原因: MyBatis Wrapper 无法在子查询中过滤 hidden=0，屏蔽/恢复消息后需手写重算未读数
    @Update("""
            UPDATE consult_session SET
              user_unread = (SELECT COUNT(*) FROM consult_message m
                             WHERE m.session_id = #{sessionId} AND m.hidden = 0 AND m.is_read = 0
                               AND m.sender_role = 'DOCTOR'),
              doctor_unread = (SELECT COUNT(*) FROM consult_message m
                               WHERE m.session_id = #{sessionId} AND m.hidden = 0 AND m.is_read = 0
                                 AND m.sender_role = 'USER'),
              update_time = NOW()
            WHERE id = #{sessionId}
            """)
    int refreshUnread(@Param("sessionId") Long sessionId);

    @Update("""
            UPDATE consult_session SET
              last_message = #{preview}, last_message_time = #{time}, update_time = NOW()
            WHERE id = #{sessionId}
            """)
    int setLastMessage(@Param("sessionId") Long sessionId,
                       @Param("preview") @Nullable String preview,
                       @Param("time") @Nullable LocalDateTime time);
}

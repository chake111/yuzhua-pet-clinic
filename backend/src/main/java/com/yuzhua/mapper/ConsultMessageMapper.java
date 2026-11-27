package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.domain.enums.ConsultSenderRole;
import com.yuzhua.entity.ConsultMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;

import java.util.List;

public interface ConsultMessageMapper extends BaseMapper<ConsultMessage> {

    /**
     * 对单条消息执行 {@code FOR UPDATE} 当前读（绕过 REPEATABLE READ 旧快照）。
     * 为避免与发送或已读路径死锁，调用方须按约定先持有所属会话行锁（锁顺序：会话行 -> 消息行）。
     */
    @Select("SELECT * FROM consult_message WHERE id = #{id} FOR UPDATE")
    @Nullable ConsultMessage selectByIdForUpdate(@Param("id") Long id);

    /**
     * 当前读最后一条可见消息。摘要重算必须用它：事务可能在等会话锁之前
     * 已执行过普通 SELECT，后续普通查询会沿用那个旧快照，看不到等待期间提交的新消息。
     */
    @Select("""
            SELECT * FROM consult_message
            WHERE session_id = #{sessionId} AND hidden = 0
            ORDER BY id DESC
            LIMIT 1
            FOR UPDATE
            """)
    @Nullable ConsultMessage selectLastVisibleForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 锁定本会话中对侧可见且未读的消息ID：FOR UPDATE 防止选中之后又被屏蔽，
     * 回执集合与实际更新集合必须同源。必须在事务内调用。
     */
    @Select("""
            SELECT id FROM consult_message
            WHERE session_id = #{sessionId} AND sender_role <> #{myRole}
              AND hidden = 0 AND is_read = 0
            ORDER BY id FOR UPDATE
            """)
    List<Long> unreadPeerIdsForUpdate(@Param("sessionId") Long sessionId,
                                      @Param("myRole") ConsultSenderRole myRole);

    // 原因: ID 集合来自同一事务的 FOR UPDATE 查询，调用方须确保非空避免 IN () 语法错误
    @Update("""
            <script>
            UPDATE consult_message SET is_read = 1
            WHERE session_id = #{sessionId} AND sender_role &lt;&gt; #{myRole}
              AND hidden = 0 AND is_read = 0
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    int markPeerRead(@Param("sessionId") Long sessionId,
                     @Param("myRole") ConsultSenderRole myRole,
                     @Param("ids") List<Long> ids);
}

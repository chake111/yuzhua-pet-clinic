package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.ConsultMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ConsultMessageMapper extends BaseMapper<ConsultMessage> {

    /**
     * 当前读一条消息并加行锁。调用方必须已经持有该会话的行锁，
     * 用于在锁内重新确认消息状态（普通 SELECT 在 REPEATABLE READ 下仍是旧快照）。
     */
    @Select("SELECT * FROM consult_message WHERE id = #{id} FOR UPDATE")
    ConsultMessage selectByIdForUpdate(@Param("id") Long id);

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
    ConsultMessage selectLastVisibleForUpdate(@Param("sessionId") Long sessionId);

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
                                      @Param("myRole") String myRole);

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
                     @Param("myRole") String myRole,
                     @Param("ids") List<Long> ids);
}

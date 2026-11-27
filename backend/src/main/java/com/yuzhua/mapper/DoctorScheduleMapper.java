package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.DoctorSchedule;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jspecify.annotations.Nullable;

public interface DoctorScheduleMapper extends BaseMapper<DoctorSchedule> {

    /**
     * 原子扣减余量并递增发号计数（配合前置 {@link #selectByIdForUpdate} 串行化排队号读取与删改并发）。
     * 返回 1 成功；返回 0 表示 {@code status != 1}、{@code remain_num <= 0} 或记录不存在（调用方须抛异常回滚）。
     */
    @Update("UPDATE doctor_schedule SET remain_num = remain_num - 1, "
            + "last_queue_no = last_queue_no + 1 "
            + "WHERE id = #{id} AND status = 1 AND remain_num > 0")
    int tryOccupy(@Param("id") Long id);

    /**
     * 当前读刚发出的排队序号，须与 {@link #tryOccupy} 同事务避免 REPEATABLE READ 读到递增前快照。
     */
    @Select("SELECT last_queue_no FROM doctor_schedule WHERE id = #{id} FOR UPDATE")
    @Nullable Integer selectLastQueueNoForUpdate(@Param("id") Long id);

    // 原因: LEAST 仅作防御性封顶，一次取消只退一次号由预约行锁内条件更新保证
    @Update("UPDATE doctor_schedule SET remain_num = LEAST(remain_num + 1, total_num) "
            + "WHERE id = #{id}")
    int release(@Param("id") Long id);

    /** 锁定排班行供校验删除/改总量或下单前确认排班仍存在；停诊/恢复仅写 status 不走此锁。 */
    @Select("SELECT * FROM doctor_schedule WHERE id = #{id} FOR UPDATE")
    @Nullable DoctorSchedule selectByIdForUpdate(@Param("id") Long id);
}

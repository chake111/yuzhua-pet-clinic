package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.DoctorSchedule;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DoctorScheduleMapper extends BaseMapper<DoctorSchedule> {

    /**
     * 原子扣减余量并递增发号计数，返回 1 成功、0 已满（调用方须回滚事务）。
     * 本表为物理删除，调用前须先持有 {@link #selectByIdForUpdate} 行锁。
     */
    @Update("UPDATE doctor_schedule SET remain_num = remain_num - 1, "
            + "last_queue_no = last_queue_no + 1 "
            + "WHERE id = #{id} AND status = 1 AND remain_num > 0")
    int tryOccupy(@Param("id") Long id);

    /**
     * 当前读刚发出的排队序号，须与 {@link #tryOccupy} 同事务避免 REPEATABLE READ 读到递增前快照。
     */
    @Select("SELECT last_queue_no FROM doctor_schedule WHERE id = #{id} FOR UPDATE")
    Integer selectLastQueueNoForUpdate(@Param("id") Long id);

    // 原因: LEAST 仅作防御性封顶，一次取消只退一次号由预约行锁内条件更新保证
    @Update("UPDATE doctor_schedule SET remain_num = LEAST(remain_num + 1, total_num) "
            + "WHERE id = #{id}")
    int release(@Param("id") Long id);

    /** 锁定排班行供校验删除/改总量或下单前确认排班仍存在；停诊/恢复仅写 status 不走此锁。 */
    @Select("SELECT * FROM doctor_schedule WHERE id = #{id} FOR UPDATE")
    DoctorSchedule selectByIdForUpdate(@Param("id") Long id);
}

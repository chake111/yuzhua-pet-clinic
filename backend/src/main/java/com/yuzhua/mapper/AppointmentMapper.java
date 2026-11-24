package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.Appointment;
import lombok.Data;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface AppointmentMapper extends BaseMapper<Appointment> {

    /** 锁定单张预约行供状态更新前重判归属与当前状态，避免快照读并发退号。 */
    @Select("SELECT * FROM appointment WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Appointment selectByIdForUpdate(@Param("id") Long id);

    /** 幂等键当前读：raw @Select 须显式过滤 deleted = 0，避免已删历史单阻断重用同键。 */
    @Select("SELECT * FROM appointment WHERE user_id = #{userId} AND idem_key = #{idemKey} AND deleted = 0 LIMIT 1")
    Appointment selectByUserAndIdemKey(@Param("userId") Long userId, @Param("idemKey") String idemKey);

    @Select("SELECT id FROM appointment "
            + "WHERE deleted = 0 AND status = 0 AND pay_status = 0 AND create_time <= #{cutoff} "
            + "ORDER BY id")
    List<Long> selectUnpaidOverdueIds(@Param("cutoff") LocalDateTime cutoff);

    @Select("SELECT id FROM appointment "
            + "WHERE deleted = 0 AND status = 0 AND pay_status = 1 AND appointment_date < CURDATE() ORDER BY id")
    List<Long> selectStaleVisitIds();

    // 原因: doctorId 参数为 null 时统计全院，非 null 仅统计该医生，显式指定 jdbcType=BIGINT 防驱动 setNull 报错
    @Select("""
            SELECT DATE_FORMAT(appointment_date, '%Y-%m-%d') AS day,
                   COUNT(*)                                  AS cnt,
                   SUM(CASE WHEN pay_status = 1 THEN amount ELSE 0 END) AS income
            FROM appointment
            WHERE deleted = 0
              AND (#{doctorId,jdbcType=BIGINT} IS NULL OR doctor_id = #{doctorId,jdbcType=BIGINT})
              AND appointment_date BETWEEN DATE_SUB(CURDATE(), INTERVAL #{days} DAY) AND CURDATE()
            GROUP BY day ORDER BY day
            """)
    List<Map<String, Object>> statDaily(@Param("days") int days, @Param("doctorId") Long doctorId);

    @Select("""
            SELECT status, COUNT(*) AS cnt FROM appointment
            WHERE deleted = 0
              AND (#{doctorId,jdbcType=BIGINT} IS NULL OR doctor_id = #{doctorId,jdbcType=BIGINT})
              AND appointment_date BETWEEN DATE_SUB(CURDATE(), INTERVAL #{days} DAY) AND CURDATE()
            GROUP BY status ORDER BY status
            """)
    List<Map<String, Object>> statStatus(@Param("days") int days, @Param("doctorId") Long doctorId);

    @Select("""
            SELECT dep.name AS name, COUNT(*) AS cnt
            FROM appointment a JOIN department dep ON dep.id = a.department_id
            WHERE a.deleted = 0 AND a.status <> 3
              AND (#{doctorId,jdbcType=BIGINT} IS NULL OR a.doctor_id = #{doctorId,jdbcType=BIGINT})
              AND a.appointment_date BETWEEN DATE_SUB(CURDATE(), INTERVAL #{days} DAY) AND CURDATE()
            GROUP BY dep.name ORDER BY cnt DESC
            """)
    List<Map<String, Object>> statDepartment(@Param("days") int days, @Param("doctorId") Long doctorId);

    @Select("""
            SELECT d.name AS name, COUNT(*) AS cnt
            FROM appointment a JOIN doctor d ON d.id = a.doctor_id
            WHERE a.deleted = 0 AND a.status = 2
              AND a.appointment_date BETWEEN DATE_SUB(CURDATE(), INTERVAL #{days} DAY) AND CURDATE()
            GROUP BY d.id, d.name ORDER BY cnt DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> statTopDoctors(@Param("days") int days, @Param("limit") int limit);

    // 原因: 统一用 CURDATE() 与 IFNULL(SUM, 0) 一次聚合今日五格，避免 JVM/DB 时区偏差与 null 拆箱异常
    @Select("""
            SELECT COUNT(*)                                                                AS orders,
                   IFNULL(SUM(status = 0), 0)                                             AS pending,
                   IFNULL(SUM(status = 1), 0)                                             AS accepted,
                   IFNULL(SUM(status = 2), 0)                                             AS finished,
                   IFNULL(SUM(CASE WHEN pay_status = 1 THEN amount ELSE 0 END), 0)        AS income
            FROM appointment
            WHERE deleted = 0 AND appointment_date = CURDATE()
              AND (#{doctorId,jdbcType=BIGINT} IS NULL OR doctor_id = #{doctorId,jdbcType=BIGINT})
            """)
    TodayCard todayCard(@Param("doctorId") Long doctorId);

    @Data
    class TodayCard {
        private long orders;
        private long pending;
        private long accepted;
        private long finished;
        private BigDecimal income;
    }

    @Select("""
            SELECT COUNT(DISTINCT user_id) AS users, COUNT(DISTINCT pet_id) AS pets
            FROM appointment
            WHERE deleted = 0 AND status <> 3
              AND (#{doctorId,jdbcType=BIGINT} IS NULL OR doctor_id = #{doctorId,jdbcType=BIGINT})
            """)
    ServedCount countServed(@Param("doctorId") Long doctorId);

    @Data
    class ServedCount {
        private Long users;
        private Long pets;
    }
}

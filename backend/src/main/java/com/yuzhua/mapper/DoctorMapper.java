package com.yuzhua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhua.entity.Doctor;

public interface DoctorMapper extends BaseMapper<Doctor> {

    @org.apache.ibatis.annotations.Update("UPDATE doctor SET appointment_count = appointment_count + 1 WHERE id = #{doctorId}")
    int incrementAppointmentCount(@org.apache.ibatis.annotations.Param("doctorId") Long doctorId);

    @org.apache.ibatis.annotations.Update("""
            UPDATE doctor d SET
              d.review_count = (SELECT COUNT(*) FROM doctor_review r
                                WHERE r.doctor_id = d.id AND r.deleted = 0 AND r.status = 1),
              d.rating = IFNULL((SELECT ROUND(AVG(r.rating), 1) FROM doctor_review r
                                 WHERE r.doctor_id = d.id AND r.deleted = 0 AND r.status = 1), 5.0)
            WHERE d.id = #{doctorId}
            """)
    int refreshStats(@org.apache.ibatis.annotations.Param("doctorId") Long doctorId);

    @org.apache.ibatis.annotations.Update("""
            UPDATE doctor d SET appointment_count =
              (SELECT COUNT(*) FROM appointment a WHERE a.doctor_id = d.id AND a.deleted = 0 AND a.status = 2)
            WHERE d.id = #{doctorId}
            """)
    int refreshAppointmentCount(@org.apache.ibatis.annotations.Param("doctorId") Long doctorId);
}

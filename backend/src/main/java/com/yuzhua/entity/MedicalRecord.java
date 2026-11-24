package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("medical_record")
public class MedicalRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String recordNo;
    private Long appointmentId;
    private Long userId;
    private Long petId;
    private Long doctorId;
    private Long departmentId;
    private LocalDate visitDate;

    private String chiefComplaint;
    private String physicalExam;
    private String diagnosis;
    private String prescription;
    private String treatment;
    private String advice;
    private LocalDate nextVisitDate;
    private String images;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}

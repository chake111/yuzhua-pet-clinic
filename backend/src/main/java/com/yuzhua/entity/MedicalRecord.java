package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("medical_record")
public class MedicalRecord {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable String recordNo;
    private @Nullable Long appointmentId;
    private @Nullable Long userId;
    private @Nullable Long petId;
    private @Nullable Long doctorId;
    private @Nullable Long departmentId;
    private @Nullable LocalDate visitDate;

    private @Nullable String chiefComplaint;
    private @Nullable String physicalExam;
    private @Nullable String diagnosis;
    private @Nullable String prescription;
    private @Nullable String treatment;
    private @Nullable String advice;
    private @Nullable LocalDate nextVisitDate;
    private @Nullable String images;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}

package com.yuzhua.vo;

import com.yuzhua.entity.MedicalRecord;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MedicalRecordVo extends MedicalRecord {

    private String petName;
    private String petSpecies;
    private String petBreed;
    private String petAvatar;
    private String doctorName;
    private String doctorTitle;
    private String departmentName;
}

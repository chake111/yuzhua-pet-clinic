package com.yuzhua.vo;

import com.yuzhua.entity.MedicalRecord;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class MedicalRecordVo extends MedicalRecord {

    private @Nullable String petName;
    private @Nullable String petSpecies;
    private @Nullable String petBreed;
    private @Nullable String petAvatar;
    private @Nullable String doctorName;
    private @Nullable String doctorTitle;
    private @Nullable String departmentName;
}

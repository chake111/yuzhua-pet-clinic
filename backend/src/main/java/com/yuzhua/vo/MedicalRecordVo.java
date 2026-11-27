package com.yuzhua.vo;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MedicalRecordVo(
        @Nullable Long id,
        @Nullable String recordNo,
        @Nullable Long appointmentId,
        @Nullable Long userId,
        @Nullable Long petId,
        @Nullable Long doctorId,
        @Nullable Long departmentId,
        @Nullable LocalDate visitDate,
        @Nullable String chiefComplaint,
        @Nullable String physicalExam,
        @Nullable String diagnosis,
        @Nullable String prescription,
        @Nullable String treatment,
        @Nullable String advice,
        @Nullable LocalDate nextVisitDate,
        @Nullable String images,
        @Nullable LocalDateTime createTime,
        @Nullable String petName,
        @Nullable String petSpecies,
        @Nullable String petBreed,
        @Nullable String petAvatar,
        @Nullable String doctorName,
        @Nullable String doctorTitle,
        @Nullable String departmentName
) {
}

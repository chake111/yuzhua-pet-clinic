package com.yuzhua.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

@Data
public class MedicalRecordForm {

    private @Nullable String chiefComplaint;
    private @Nullable String physicalExam;
    private @Nullable String diagnosis;
    private @Nullable String prescription;
    private @Nullable String treatment;
    private @Nullable String advice;
    private @Nullable LocalDate nextVisitDate;
    private @Nullable String images;

    @JsonIgnore
    private boolean nextVisitDateSubmitted;

    public void setNextVisitDate(@Nullable LocalDate date) {
        this.nextVisitDate = date;
        this.nextVisitDateSubmitted = true;
    }
}

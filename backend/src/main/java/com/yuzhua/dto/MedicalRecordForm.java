package com.yuzhua.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;

@Data
public class MedicalRecordForm {

    private String chiefComplaint;
    private String physicalExam;
    private String diagnosis;
    private String prescription;
    private String treatment;
    private String advice;
    private LocalDate nextVisitDate;
    private String images;

    @JsonIgnore
    private boolean nextVisitDateSubmitted;

    public void setNextVisitDate(LocalDate date) {
        this.nextVisitDate = date;
        this.nextVisitDateSubmitted = true;
    }
}

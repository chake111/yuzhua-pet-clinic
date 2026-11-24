package com.yuzhua.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PetForm {

    private String name;
    private String species;
    private String breed;
    private Integer gender;
    private LocalDate birthday;
    private Integer ageMonth;
    private BigDecimal weight;
    private String color;
    private Integer isNeutered;
    private Integer vaccineStatus;
    private Integer dewormFlag;
    private String avatar;
    private String allergy;
    private String medicalHistory;
    private String remark;

    @JsonIgnore
    private boolean birthdaySubmitted;
    @JsonIgnore
    private boolean weightSubmitted;

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
        this.birthdaySubmitted = true;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
        this.weightSubmitted = true;
    }
}

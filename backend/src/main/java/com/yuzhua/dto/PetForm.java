package com.yuzhua.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PetForm {

    private @Nullable String name;
    private @Nullable String species;
    private @Nullable String breed;
    private @Nullable Integer gender;
    private @Nullable LocalDate birthday;
    private @Nullable Integer ageMonth;
    private @Nullable BigDecimal weight;
    private @Nullable String color;
    private @Nullable Integer isNeutered;
    private @Nullable Integer vaccineStatus;
    private @Nullable Integer dewormFlag;
    private @Nullable String avatar;
    private @Nullable String allergy;
    private @Nullable String medicalHistory;
    private @Nullable String remark;

    @JsonIgnore
    private boolean birthdaySubmitted;
    @JsonIgnore
    private boolean weightSubmitted;

    public void setBirthday(@Nullable LocalDate birthday) {
        this.birthday = birthday;
        this.birthdaySubmitted = true;
    }

    public void setWeight(@Nullable BigDecimal weight) {
        this.weight = weight;
        this.weightSubmitted = true;
    }
}

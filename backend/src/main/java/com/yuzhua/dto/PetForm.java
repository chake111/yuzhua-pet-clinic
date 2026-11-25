package com.yuzhua.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PetForm {

    @NotBlank(message = "请填写宠物名称")
    private @Nullable String name;
    @NotBlank(message = "请填写宠物品种分类")
    private @Nullable String species;
    private @Nullable String breed;
    private @Nullable Integer gender;
    @Schema(nullable = true)
    private @Nullable LocalDate birthday;
    private @Nullable Integer ageMonth;
    @Schema(nullable = true)
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

package com.yuzhua.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yuzhua.entity.DoctorReview;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties({"userId"})
public class ReviewVo extends DoctorReview {

    private @Nullable String userNickname;
    private @Nullable String userAvatar;

    private @Nullable String doctorName;
    private @Nullable String doctorTitle;

    private @Nullable String orderNo;
    private @Nullable LocalDate appointmentDate;
    private @Nullable String petName;
    private @Nullable String petSpecies;

    public void maskAnonymous() {
        if (!Integer.valueOf(1).equals(getIsAnonymous())) {
            return;
        }
        setUserNickname("匿名用户");
        setUserAvatar(null);
    }
}

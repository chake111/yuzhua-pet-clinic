package com.yuzhua.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yuzhua.entity.DoctorReview;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties({"userId"})
public class ReviewVo extends DoctorReview {

    private String userNickname;
    private String userAvatar;

    private String doctorName;
    private String doctorTitle;

    private String orderNo;
    private LocalDate appointmentDate;
    private String petName;
    private String petSpecies;

    public void maskAnonymous() {
        if (!Integer.valueOf(1).equals(getIsAnonymous())) {
            return;
        }
        setUserNickname("匿名用户");
        setUserAvatar(null);
    }
}

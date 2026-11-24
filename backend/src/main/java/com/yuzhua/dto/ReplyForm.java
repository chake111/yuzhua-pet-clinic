package com.yuzhua.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReplyForm {

    @NotBlank(message = "请填写回复内容")
    private String reply;
}

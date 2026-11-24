package com.yuzhua.dto;

import lombok.Data;

@Data
public class NoticeForm {

    private String title;
    private String cover;
    private String summary;
    private String content;

    private Integer type;

    private Integer topFlag;

    private Integer status;
}

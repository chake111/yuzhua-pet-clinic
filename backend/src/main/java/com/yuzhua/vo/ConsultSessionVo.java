package com.yuzhua.vo;

import com.yuzhua.entity.ConsultSession;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConsultSessionVo extends ConsultSession {

    private String petName;
    private String doctorName;
    private String doctorAvatar;
    private String userNickname;
    private String userPhone;

    private Integer myUnread;
}

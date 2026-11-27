package com.yuzhua.vo;

import lombok.Data;

@Data
public class LoginVo {

    private String token;

    private Long id;
    private String username;
    private String name;
    private String avatar;
    private String phone;
    private String email;

    private String typ;
    private String role;
    private Long doctorId;
    private String doctorName;
    private String departmentName;
}

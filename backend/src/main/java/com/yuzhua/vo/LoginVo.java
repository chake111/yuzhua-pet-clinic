package com.yuzhua.vo;

import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class LoginVo {

    private @Nullable String token;

    private @Nullable Long id;
    private @Nullable String username;
    private @Nullable String name;
    private @Nullable String avatar;
    private @Nullable String phone;
    private @Nullable String email;

    private @Nullable String typ;
    private @Nullable String role;
    private @Nullable Long doctorId;
    private @Nullable String doctorName;
    private @Nullable String departmentName;
}

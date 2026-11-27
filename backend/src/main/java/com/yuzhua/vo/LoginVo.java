package com.yuzhua.vo;

import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
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

    private @Nullable TokenType typ;
    private @Nullable UserRole role;
    private @Nullable Long doctorId;
    private @Nullable String doctorName;
    private @Nullable String departmentName;
}

package com.yuzhua.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.jspecify.annotations.Nullable;

public class AuthDto {

    @Data
    public static class Login {
        @NotBlank(message = "请输入账号")
        private @Nullable String username;
        @NotBlank(message = "请输入密码")
        private @Nullable String password;
    }

    @Data
    public static class Register {
        @NotBlank(message = "请输入账号")
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{3,19}$",
                message = "账号需以字母开头，由 4-20 位字母、数字或下划线组成")
        private @Nullable String username;

        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 20, message = "密码长度需为 6-20 位")
        private @Nullable String password;

        @NotBlank(message = "请输入昵称")
        @Size(max = 20, message = "昵称不超过 20 字")
        private @Nullable String nickname;

        @NotBlank(message = "请输入手机号")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private @Nullable String phone;

        private @Nullable String email;
        private @Nullable Integer gender;
    }

    @Data
    public static class UpdateProfile {
        @Size(max = 20, message = "昵称不超过 20 字")
        private @Nullable String nickname;
        private @Nullable String avatar;
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private @Nullable String phone;
        private @Nullable String email;
        private @Nullable Integer gender;
        private @Nullable String city;
        private @Nullable String address;
    }

    @Data
    public static class ChangePassword {
        @NotBlank(message = "请输入原密码")
        private @Nullable String oldPassword;
        @NotBlank(message = "请输入新密码")
        @Size(min = 6, max = 20, message = "密码长度需为 6-20 位")
        private @Nullable String newPassword;
    }
}

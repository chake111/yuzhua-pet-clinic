package com.yuzhua.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

public class AuthDto {

    @Data
    public static class Login {
        @NotBlank(message = "请输入账号")
        private String username;
        @NotBlank(message = "请输入密码")
        private String password;
    }

    @Data
    public static class Register {
        @NotBlank(message = "请输入账号")
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{3,19}$",
                message = "账号需以字母开头，由 4-20 位字母、数字或下划线组成")
        private String username;

        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 20, message = "密码长度需为 6-20 位")
        private String password;

        @NotBlank(message = "请输入昵称")
        @Size(max = 20, message = "昵称不超过 20 字")
        private String nickname;

        @NotBlank(message = "请输入手机号")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        private String email;
        private Integer gender;
    }

    @Data
    public static class UpdateProfile {
        @Size(max = 20, message = "昵称不超过 20 字")
        private String nickname;
        private String avatar;
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;
        private String email;
        private Integer gender;
        private String city;
        private String address;
    }

    @Data
    public static class ChangePassword {
        @NotBlank(message = "请输入原密码")
        private String oldPassword;
        @NotBlank(message = "请输入新密码")
        @Size(min = 6, max = 20, message = "密码长度需为 6-20 位")
        private String newPassword;
    }
}

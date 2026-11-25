package com.yuzhua.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public final class AuthDto {

    private AuthDto() {
    }

    public record Login(
            @NotBlank(message = "请输入账号")
            String username,
            @NotBlank(message = "请输入密码")
            String password
    ) {
    }

    public record Register(
            @NotBlank(message = "请输入账号")
            @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{3,19}$",
                    message = "账号需以字母开头，由 4-20 位字母、数字或下划线组成")
            String username,
            @NotBlank(message = "请输入密码")
            @Size(min = 6, max = 20, message = "密码长度需为 6-20 位")
            String password,
            @NotBlank(message = "请输入昵称")
            @Size(max = 20, message = "昵称不超过 20 字")
            String nickname,
            @NotBlank(message = "请输入手机号")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone,
            @Nullable String email,
            @Nullable Integer gender
    ) {
    }

    public record UpdateProfile(
            @Size(max = 20, message = "昵称不超过 20 字")
            @Nullable String nickname,
            @Nullable String avatar,
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
            @Nullable String phone,
            @Nullable String email,
            @Nullable Integer gender,
            @Nullable String city,
            @Nullable String address
    ) {
    }

    public record ChangePassword(
            @NotBlank(message = "请输入原密码")
            String oldPassword,
            @NotBlank(message = "请输入新密码")
            @Size(min = 6, max = 20, message = "密码长度需为 6-20 位")
            String newPassword
    ) {
    }
}

package com.hmdp.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求参数 DTO
 * <p>
 * 接收用户登录时提交的手机号、验证码和密码，携带 {@code @Valid} 校验注解，
 * 在进入 Controller 前自动完成参数合法性验证。
 * </p>
 */
@Data
public class LoginFormDTO {
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码长度为6位")
    private String code;

    @Size(min = 6, max = 20, message = "密码长度6-20位")
    private String password;
}

package com.hmdp.dto;

import lombok.Data;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 更新用户信息请求 DTO
 * <p>
 * 用于接收前端提交的用户资料更新请求，包含昵称、头像、
 * 性别、城市、生日、简介等字段，各字段均有校验约束。
 * </p>
 */
@Data
public class UpdateUserInfoDTO {
    @Size(max = 30, message = "昵称最长30个字符")
    private String nickName;
    
    private String icon;
    
    private Boolean gender;
    
    @Size(max = 50, message = "城市名称最长50个字符")
    private String city;
    
    private LocalDate birthday;
    
    @Size(max = 200, message = "简介最长200个字符")
    private String introduce;
}

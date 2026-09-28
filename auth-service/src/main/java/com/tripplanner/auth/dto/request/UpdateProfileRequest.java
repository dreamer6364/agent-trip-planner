package com.tripplanner.auth.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新个人信息请求
 */
@Data
public class UpdateProfileRequest {

    @Size(max = 100, message = "昵称长度不能超过 100 字符")
    private String name;

    @Size(max = 500, message = "头像 URL 长度不能超过 500 字符")
    private String avatarUrl;
}
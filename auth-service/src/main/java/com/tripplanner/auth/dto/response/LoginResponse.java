package com.tripplanner.auth.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 登录/刷新响应
 */
@Data
@Builder
public class LoginResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private int expiresIn; // access token 有效期(秒)
    private UserProfileResponse user;
}
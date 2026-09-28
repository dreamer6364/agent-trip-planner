package com.tripplanner.auth.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户档案响应
 */
@Data
@Builder
public class UserProfileResponse {

    private String id;
    private String email;
    private String name;
    private String avatarUrl;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}
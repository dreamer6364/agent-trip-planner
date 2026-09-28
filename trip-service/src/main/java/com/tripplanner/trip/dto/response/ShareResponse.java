package com.tripplanner.trip.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 分享响应
 */
@Data
@Builder
public class ShareResponse {

    // 分享 Token
    private String shareToken;

    // 分享链接 (完整 URL)
    private String shareUrl;

    // 是否公开
    private Boolean isPublic;

    // 过期时间
    private String expiresAt;

    // 查看次数
    private Integer viewCount;
}
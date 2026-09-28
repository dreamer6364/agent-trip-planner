package com.tripplanner.trip.dto.request;

import lombok.Data;

/**
 * 分享行程请求
 */
@Data
public class ShareTripRequest {

    // 是否公开
    private Boolean isPublic = true;

    // 过期天数 (可选，null 表示永不过期)
    private Integer expireDays;

    // 访问密码 (可选)
    private String password;

    // 允许导出
    private Boolean allowExport = true;

    // 自定义分享标题
    private String shareTitle;

    // 自定义分享描述
    private String shareDescription;
}
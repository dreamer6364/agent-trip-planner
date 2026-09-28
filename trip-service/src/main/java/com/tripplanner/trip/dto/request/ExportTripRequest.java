package com.tripplanner.trip.dto.request;

import lombok.Data;

/**
 * 导出行程请求
 */
@Data
public class ExportTripRequest {

    // 导出格式: json, ics, pdf, png, template
    private String format = "json";

    // 版本号，不指定则导出最新版本
    private Integer version;

    // 是否包含地图图片 (仅 pdf/png)
    private Boolean includeMap = true;

    // 是否包含统计信息
    private Boolean includeStats = true;

    // 语言
    private String language = "zh-CN";

    // 时区
    private String timezone = "Asia/Shanghai";
}
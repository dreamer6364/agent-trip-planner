package com.tripplanner.trip.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 更新行程基本信息请求
 */
@Data
public class UpdateTripRequest {

    @Size(max = 200, message = "标题长度不能超过 200 字符")
    private String title;

    private LocalDateTime timeStart;
    private LocalDateTime timeEnd;

    private String transportMode;

    @Pattern(regexp = "(?i)compact|moderate|relaxed",
            message = "活动频率只能是 compact(紧凑)/moderate(适中)/relaxed(宽松)")
    private String pace; // 活动频率：compact/moderate/relaxed

    private String status;

    private Map<String, Object> preferences;

    private Boolean isPublic;
}
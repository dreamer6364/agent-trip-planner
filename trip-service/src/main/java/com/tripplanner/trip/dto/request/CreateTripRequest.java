package com.tripplanner.trip.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 创建行程请求
 */
@Data
public class CreateTripRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题长度不能超过 200 字符")
    private String title;

    @NotBlank(message = "行程描述不能为空")
    @Size(max = 10000, message = "描述长度不能超过 10000 字符")
    private String rawInput;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime timeStart;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime timeEnd;

    private String transportMode = "mixed"; // walk, transit, drive, bike, mixed

    @Pattern(regexp = "(?i)compact|moderate|relaxed",
            message = "活动频率只能是 compact(紧凑)/moderate(适中)/relaxed(宽松)")
    private String pace = "moderate"; // 活动频率：compact(紧凑 8~10小时/天), moderate(适中 6~8小时/天), relaxed(宽松 3~5小时/天)

    private Map<String, Object> preferences; // 偏好配置

    @Size(max = 50, message = "城市名最长 50 字符")
    private String city; // 可选：显式目的地城市（AI 无法从描述识别城市时由用户指定，优先级最高）

    private List<PlaceInput> places; // 可选：结构化地点列表

    /**
     * 可选创建模式："draft"=仅保存草稿（不触发 AI 规划）；缺省或其他值=完整规划流程。
     * 草稿后续可经 POST /{id}/plan 或规划页发起规划。
     */
    private String status;

    @Data
    public static class PlaceInput {
        private String name;
        private String priority; // must, recommended, optional, excluded
        private String type; // scenic, restaurant, hotel, shopping, transport
        private String meal; // breakfast, lunch, dinner
        private Integer preferredDurationMin;
        private String notes;
    }
}
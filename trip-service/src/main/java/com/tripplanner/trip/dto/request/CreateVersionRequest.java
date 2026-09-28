package com.tripplanner.trip.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 基于反馈创建新版本请求
 */
@Data
public class CreateVersionRequest {

    @NotBlank(message = "用户反馈不能为空")
    @Size(max = 2000, message = "反馈长度不能超过 2000 字符")
    private String feedback;

    @NotNull(message = "基础版本 ID 不能为空")
    private String baseVersionId;

    // 可选：结构化变更集 (由前端解析反馈后生成)
    private Object changes;
}
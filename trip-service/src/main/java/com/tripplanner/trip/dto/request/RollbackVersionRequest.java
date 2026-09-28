package com.tripplanner.trip.dto.request;

import lombok.Data;

/**
 * 回滚版本请求
 */
@Data
public class RollbackVersionRequest {

    private String reason; // 可选：回滚原因
}
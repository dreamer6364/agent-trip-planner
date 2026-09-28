package com.tripplanner.trip.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 行程版本响应
 */
@Data
@Builder
public class TripVersionResponse {

    private String id;
    private String tripId;
    private Integer versionNum;
    private String parentVersionId;
    private List<Object> activities; // JSON array
    private List<Object> routes; // JSON array
    private List<Object> conflicts; // JSON array
    private Object stats; // JSON object
    private String feedback;
    private Object solverMeta; // JSON object
    private String status;
    private LocalDateTime createdAt;
}
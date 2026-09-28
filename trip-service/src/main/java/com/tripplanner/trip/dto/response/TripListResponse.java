package com.tripplanner.trip.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 行程分页列表响应
 */
@Data
@Builder
public class TripListResponse {

    private List<TripResponse> items;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public boolean hasNext() {
        return page < totalPages;
    }

    public boolean hasPrevious() {
        return page > 1;
    }
}
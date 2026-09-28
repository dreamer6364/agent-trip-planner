package com.tripplanner.trip.controller;

import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.trip.dto.response.CoverResponse;
import com.tripplanner.trip.service.CoverImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行程封面图控制器
 *
 * <p>按关键词从网络搜索封面图，供行程卡片展示。该接口不涉及用户数据，
 * 因此对网关与本服务均放行（未登录的公开行程页同样可用）。</p>
 */
@Tag(name = "行程封面", description = "行程卡片封面图网络搜索")
@RestController
@RequestMapping("/api/trips/covers")
@RequiredArgsConstructor
public class CoverController {

    private final CoverImageService coverImageService;

    @Operation(summary = "按关键词搜索封面图", description = "输入城市/景点关键词，返回可直出的图片直链；无结果时 data 为 null")
    @GetMapping
    public ResponseEntity<ApiResponse<CoverResponse>> searchCover(
            @RequestParam(name = "kw", required = false) String keyword
    ) {
        return ResponseEntity.ok(ApiResponse.success(coverImageService.searchCover(keyword)));
    }
}

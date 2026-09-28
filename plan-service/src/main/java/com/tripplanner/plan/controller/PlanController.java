package com.tripplanner.plan.controller;

import com.tripplanner.common.model.ParsedInput;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.plan.dto.request.ParsePreviewRequest;
import com.tripplanner.plan.dto.request.GeocodeRequest;
import com.tripplanner.plan.dto.request.PoiSearchRequest;
import com.tripplanner.plan.dto.response.GeocodeResponse;
import com.tripplanner.plan.dto.response.ParsePreviewResponse;
import com.tripplanner.plan.dto.response.PlanTaskProgressResponse;
import com.tripplanner.plan.service.GeocodeService;
import com.tripplanner.plan.service.InputParserService;
import com.tripplanner.plan.service.PlanTaskService;
import com.tripplanner.plan.service.PoiSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 规划控制器
 * 解析预览、任务进度查询
 */
@Tag(name = "规划服务", description = "输入解析、任务管理、地理编码、路线规划")
@RestController
@RequestMapping("/api/plan")
@RequiredArgsConstructor
public class PlanController {

    private final InputParserService inputParserService;
    private final PlanTaskService planTaskService;
    private final GeocodeService geocodeService;
    private final PoiSearchService poiSearchService;

    @Operation(summary = "解析预览 (同步，不触发规划)")
    @PostMapping("/parse-preview")
    public ResponseEntity<ApiResponse<ParsePreviewResponse>> parsePreview(
            @Valid @RequestBody ParsePreviewRequest request
    ) {
        ParsedInput parsedInput = inputParserService.parsePreview(
                request.getRawInput(),
                request.getTimeStart(),
                request.getTimeEnd(),
                request.getTransportMode(),
                request.getPreferences()
        );

        ParsePreviewResponse response = ParsePreviewResponse.of(parsedInput, true, null);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "查询规划任务进度")
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<PlanTaskProgressResponse>> getTaskProgress(
            @PathVariable String taskId
    ) {
        PlanTaskProgressResponse progress = planTaskService.getProgress(taskId);
        return ResponseEntity.ok(ApiResponse.success(progress));
    }

    @Operation(summary = "查询行程的所有规划任务")
    @GetMapping("/trips/{tripId}/tasks")
    public ResponseEntity<ApiResponse<List<PlanTaskProgressResponse>>> getTripTasks(
            @PathVariable String tripId
    ) {
        List<PlanTaskProgressResponse> tasks = planTaskService.getTasksByTripId(tripId);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @Operation(summary = "地理编码 (地址/名称 -> 坐标)")
    @PostMapping("/geocode")
    public ResponseEntity<ApiResponse<GeocodeResponse>> geocode(
            @Valid @RequestBody GeocodeRequest request
    ) {
        GeocodeResponse response;
        if (request.getQueries() != null && !request.getQueries().isEmpty()) {
            // 批量查询 - 这里简化返回第一个
            response = geocodeService.geocode(request.getQuery(), request.getCity(), request.getSource());
        } else {
            response = geocodeService.geocode(request.getQuery(), request.getCity(), request.getSource());
        }
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "批量地理编码")
    @PostMapping("/batch-geocode")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchGeocode(
            @RequestBody GeocodeRequest request
    ) {
        List<GeocodeResponse> results = geocodeService.batchGeocode(request.getQueries(), request.getCity(), request.getSource());
        return ResponseEntity.ok(ApiResponse.success(Map.of("results", results)));
    }

    @Operation(summary = "逆地理编码 (坐标 -> 地址)")
    @PostMapping("/reverse-geocode")
    public ResponseEntity<ApiResponse<GeocodeResponse>> reverseGeocode(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "amap") String source
    ) {
        GeocodeResponse response = geocodeService.reverseGeocode(lat, lng, source);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "POI 关键词搜索（换景点搜索框，返回多条含坐标/地址/评分）")
    @PostMapping("/poi-search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> poiSearch(
            @Valid @RequestBody PoiSearchRequest request
    ) {
        List<Map<String, Object>> results = poiSearchService.searchByKeyword(
                request.getKeyword(), request.getCity(), request.getLimit());
        return ResponseEntity.ok(ApiResponse.success(results));
    }
}
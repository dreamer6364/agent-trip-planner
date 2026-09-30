package com.tripplanner.trip.controller;

import com.tripplanner.common.security.JwtAuthenticationToken;
import com.tripplanner.common.annotation.RequirePermission;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.trip.dto.request.ExportTripRequest;
import com.tripplanner.trip.dto.response.ExportResponse;
import com.tripplanner.trip.service.TripExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

/**
 * 行程导出控制器
 */
@Tag(name = "行程导出", description = "导出为 JSON/ICS/PDF/PNG/Template")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripExportController {

    private final TripExportService exportService;

    @Operation(summary = "导出行程", description = "返回导出元数据，downloadUrl 指向 /export/file 文件下载端点")
    @GetMapping("/{id}/export")
    @RequirePermission(value = "trip:read", resourceId = "#id")
    public ResponseEntity<ApiResponse<ExportResponse>> exportTrip(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @Valid ExportTripRequest request
    ) {
        ExportResponse response = exportService.exportTrip(auth.getUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "导出文件下载", description = "返回导出文件原始字节流，带鉴权以 Blob 方式下载")
    @GetMapping("/{id}/export/file")
    @RequirePermission(value = "trip:read", resourceId = "#id")
    public ResponseEntity<byte[]> downloadExportFile(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @Valid ExportTripRequest request
    ) {
        TripExportService.ExportFile file = exportService.renderFile(auth.getUserId(), id, request);

        String asciiName = file.filename().replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String encodedName = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }
}

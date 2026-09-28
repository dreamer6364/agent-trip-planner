package com.tripplanner.trip.controller;

import com.tripplanner.common.security.JwtAuthenticationToken;
import com.tripplanner.common.annotation.RequirePermission;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.trip.dto.request.ExportTripRequest;
import com.tripplanner.trip.dto.response.ExportResponse;
import com.tripplanner.trip.service.TripExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 行程导出控制器
 */
@Tag(name = "行程导出", description = "导出为 JSON/ICS/PDF/PNG/Template")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripExportController {

    private final TripExportService exportService;

    @Operation(summary = "导出行程")
    @GetMapping("/{id}/export")
    @RequirePermission(value = "trip:read", resourceId = "#id")
    public ResponseEntity<ApiResponse<ExportResponse>> exportTrip(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @Valid ExportTripRequest request
    ) throws IOException {
        ExportResponse response = exportService.exportTrip(auth.getUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

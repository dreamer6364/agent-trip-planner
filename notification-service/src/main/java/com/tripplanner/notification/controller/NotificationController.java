package com.tripplanner.notification.controller;

import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.response.Meta;
import com.tripplanner.common.security.JwtAuthenticationToken;
import com.tripplanner.notification.dto.NotificationResponse;
import com.tripplanner.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通知 REST 控制器
 * 历史查询、未读数、标记已读
 */
@Tag(name = "通知中心", description = "通知历史、未读数、实时订阅")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

@Operation(summary = "获取通知列表 (分页)")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getNotifications(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<NotificationResponse> notifications = notificationService.getNotifications(resolveUserId(auth), pageable);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @Operation(summary = "获取未读通知")
    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getUnreadNotifications(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<NotificationResponse> notifications = notificationService.getUnreadNotifications(resolveUserId(auth), limit);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    @Operation(summary = "获取未读数量")
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount(
            @AuthenticationPrincipal JwtAuthenticationToken auth
    ) {
        long count = notificationService.getUnreadCount(resolveUserId(auth));
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", count)));
    }

    @Operation(summary = "标记通知为已读")
    @PutMapping("/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @RequestBody List<String> notificationIds
    ) {
        notificationService.markAsRead(resolveUserId(auth), notificationIds);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "标记单条通知为已读")
    @PutMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Void>> markOneAsRead(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id
    ) {
        notificationService.markAsRead(resolveUserId(auth), List.of(id));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "全部标记为已读")
    @PutMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(
            @AuthenticationPrincipal JwtAuthenticationToken auth
    ) {
        notificationService.markAllAsRead(resolveUserId(auth));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "删除过期通知 (保留最近 100 条)")
    @DeleteMapping("/cleanup")
    public ResponseEntity<ApiResponse<Void>> cleanupOldNotifications(
            @AuthenticationPrincipal JwtAuthenticationToken auth
    ) {
        notificationService.cleanupOldNotifications(resolveUserId(auth));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private String resolveUserId(JwtAuthenticationToken auth) {
        if (auth == null || auth.getUserId() == null || auth.getUserId().isBlank()) {
            throw com.tripplanner.common.exception.BizException.unauthorized("未认证");
        }
        return auth.getUserId();
    }
}

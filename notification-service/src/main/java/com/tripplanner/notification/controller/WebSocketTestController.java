package com.tripplanner.notification.controller;

import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.notification.dto.ProgressMessage;
import com.tripplanner.notification.service.WebSocketSessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * WebSocket 测试控制器
 * 仅用于开发调试
 */
@Tag(name = "WebSocket 测试", description = "手动触发推送测试")
@RestController
@RequestMapping("/api/ws-test")
@RequiredArgsConstructor
public class WebSocketTestController {

    private final WebSocketSessionManager sessionManager;
    private final SimpMessagingTemplate messagingTemplate;

    @Operation(summary = "测试发送进度消息给当前用户")
    @PostMapping("/progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testProgress(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "50") int percent,
            @RequestParam(defaultValue = "SOLVE") String stage
    ) {
        ProgressMessage msg = ProgressMessage.builder()
                .type("PROGRESS")
                .taskId("test-" + System.currentTimeMillis())
                .tripId("trip-test")
                .versionId("ver-test")
                .percent(percent)
                .stage(stage)
                .message("测试进度推送: " + stage + " " + percent + "%")
                .timestamp(LocalDateTime.now())
                .build();

        sessionManager.sendToUser(userDetails.getUsername(), "/queue/progress", msg);
        return ResponseEntity.ok(ApiResponse.success(Map.of("sent", true, "message", msg)));
    }

    @Operation(summary = "测试发送完成消息")
    @PostMapping("/completed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testCompleted(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        ProgressMessage msg = ProgressMessage.builder()
                .type("COMPLETED")
                .taskId("test-" + System.currentTimeMillis())
                .tripId("trip-test")
                .versionId("ver-test")
                .percent(100)
                .stage("COMPLETED")
                .message("测试规划完成")
                .resultVersionId("ver-result-123")
                .resultUrl("/api/trips/trip-test/versions/ver-result-123")
                .timestamp(LocalDateTime.now())
                .build();

        sessionManager.sendToUser(userDetails.getUsername(), "/queue/progress", msg);
        return ResponseEntity.ok(ApiResponse.success(Map.of("sent", true, "message", msg)));
    }

    @Operation(summary = "测试发送失败消息")
    @PostMapping("/failed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testFailed(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        ProgressMessage msg = ProgressMessage.builder()
                .type("FAILED")
                .taskId("test-" + System.currentTimeMillis())
                .tripId("trip-test")
                .versionId("ver-test")
                .percent(0)
                .stage("FAILED")
                .message("测试规划失败")
                .error("测试错误: 求解器超时")
                .retryable(true)
                .timestamp(LocalDateTime.now())
                .build();

        sessionManager.sendToUser(userDetails.getUsername(), "/queue/progress", msg);
        return ResponseEntity.ok(ApiResponse.success(Map.of("sent", true, "message", msg)));
    }

    @Operation(summary = "测试广播系统通知")
    @PostMapping("/broadcast")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testBroadcast(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String message
    ) {
        sessionManager.broadcast("/topic/system", Map.of(
                "type", "SYSTEM",
                "title", "系统公告",
                "content", message,
                "timestamp", LocalDateTime.now()
        ));
        return ResponseEntity.ok(ApiResponse.success(Map.of("sent", true)));
    }

    @Operation(summary = "获取当前用户在线会话数")
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSessions(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        int count = sessionManager.getUserSessionCount(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(Map.of("userId", userDetails.getUsername(), "sessionCount", count)));
    }

    @Operation(summary = "踢出当前用户所有会话 (测试)")
    @PostMapping("/kickout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testKickout(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        sessionManager.kickoutUser(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(Map.of("kickedOut", true)));
    }
}

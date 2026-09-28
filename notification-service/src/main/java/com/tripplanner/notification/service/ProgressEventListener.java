package com.tripplanner.notification.service;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.notification.dto.ProgressMessage;
import com.tripplanner.notification.service.NotificationService;
import com.tripplanner.notification.service.WebSocketSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis Channel 进度监听器
 * 监听 Planning Worker 发布的实时进度
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressEventListener implements MessageListener {

    private final WebSocketSessionManager sessionManager;
    private final NotificationService notificationService;
    private final JsonUtils jsonUtils;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(pattern);
            String body = new String(message.getBody());
            
            log.debug("收到进度消息: channel={}, body={}", channel, body);

            // 解析进度消息
            ProgressMessage progress = jsonUtils.fromJson(body, ProgressMessage.class);
            
            if (progress.getTaskId() == null) {
                log.warn("进度消息缺少 taskId");
                return;
            }

            // 从 taskId 获取 userId (实际应从 TaskService 查询)
            // 这里简化：假设消息中包含 userId，或通过 taskId 查询
            String userId = progress.getData() != null ? 
                    (String) progress.getData().get("userId") : null;

            if (userId == null) {
                log.warn("无法确定进度消息的用户: taskId={}", progress.getTaskId());
                return;
            }

            // 1. 推送实时进度
            sessionManager.sendToUser(userId, "/queue/progress", progress);

            // 2. 关键节点创建通知
            if (shouldCreateNotification(progress)) {
                notificationService.createAndPush(
                        userId,
                        "PROGRESS",
                        "行程规划进度更新",
                        progress.getMessage(),
                        Map.of("taskId", progress.getTaskId(), "stage", progress.getStage(), "percent", progress.getPercent()),
                        "low"
                );
            }

        } catch (Exception e) {
            log.error("处理进度消息失败: {}", e.getMessage(), e);
        }
    }

    private boolean shouldCreateNotification(ProgressMessage progress) {
        // 仅在关键节点创建持久化通知
        Integer percent = progress.getPercent();
        return percent != null && (percent == 100 || percent == 50 || percent == 25);
    }
}
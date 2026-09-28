package com.tripplanner.notification.service;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.notification.dto.NotificationResponse;
import com.tripplanner.notification.dto.ProgressMessage;
import com.tripplanner.notification.entity.Notification;
import com.tripplanner.notification.repository.NotificationRepository;
import com.tripplanner.notification.service.WebSocketSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知业务服务
 * 创建、查询、标记已读、未读数统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final WebSocketSessionManager sessionManager;
    private final JsonUtils jsonUtils;

    /**
     * 创建并推送通知
     */
    public NotificationResponse createAndPush(String userId, String type, String title, 
                                               String content, Map<String, Object> data, 
                                               String priority) {
        // 1. 持久化
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setData(jsonUtils.toJson(data));
        notification.setRead(false);
        notification.setPriority(priority != null ? priority : "normal");

        notificationRepository.insert(notification);

        NotificationResponse response = toResponse(notification);

        // 2. 实时推送 (如果用户在线)
        if (sessionManager.isUserOnline(userId)) {
            sessionManager.sendToUser(userId, "/queue/notification", response);
        }

        // 3. 更新未读数缓存 (可选)
        // redisTemplate.opsForValue().increment("unread:count:" + userId);

        log.debug("创建通知: userId={}, type={}, id={}", userId, type, notification.getId());
        return response;
    }

    /**
     * 创建进度通知并推送
     */
    public void pushProgress(String userId, ProgressMessage progress) {
        if (sessionManager.isUserOnline(userId)) {
            sessionManager.sendToUser(userId, "/queue/progress", progress);
        }
        // 进度消息通常不持久化，或仅持久化关键节点
        if (progress.getPercent() != null && (progress.getPercent() % 25 == 0 || progress.getPercent() == 100)) {
            createAndPush(userId, "PROGRESS", 
                    "行程规划进度: " + progress.getPercent() + "%",
                    progress.getMessage(),
                    Map.of("taskId", progress.getTaskId(), "stage", progress.getStage(), "percent", progress.getPercent()),
                    "low");
        }
    }

    /**
     * 分页查询通知
     */
    public Page<NotificationResponse> getNotifications(String userId, Pageable pageable) {
        int offset = (int) pageable.getOffset();
        int size = pageable.getPageSize();

        List<Notification> notifications = notificationRepository.findByUserId(userId, offset, size);
        long total = notificationRepository.countUnreadByUserId(userId); // 这里简化，实际应查总数

        List<NotificationResponse> responses = notifications.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(responses, pageable, total);
    }

    /**
     * 获取未读通知
     */
    public List<NotificationResponse> getUnreadNotifications(String userId, int limit) {
        List<Notification> notifications = notificationRepository.findUnreadByUserId(userId, limit);
        return notifications.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 获取未读数量
     */
    public long getUnreadCount(String userId) {
        return notificationRepository.countUnreadByUserId(userId);
    }

    /**
     * 标记已读
     */
    public void markAsRead(String userId, List<String> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return;
        }
        notificationRepository.updateReadByIds(notificationIds, true);
        
        // 更新缓存
        // redisTemplate.opsForValue().set("unread:count:" + userId, getUnreadCount(userId));
    }

    /**
     * 全部标记已读
     */
    public void markAllAsRead(String userId) {
        List<Notification> unread = notificationRepository.findUnreadByUserId(userId, 1000);
        if (!unread.isEmpty()) {
            List<String> ids = unread.stream().map(Notification::getId).collect(Collectors.toList());
            notificationRepository.updateReadByIds(ids, true);
        }
    }

    /**
     * 删除过期通知 (保留最近 100 条)
     */
    public void cleanupOldNotifications(String userId) {
        notificationRepository.deleteOldNotifications(userId, 100);
    }

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .data(jsonUtils.fromJson(n.getData(), Map.class))
                .read(n.getRead())
                .priority(n.getPriority())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
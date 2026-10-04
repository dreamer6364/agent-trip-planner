package com.tripplanner.auth.controller;

import com.tripplanner.auth.security.JwtAuthenticationToken;
import com.tripplanner.auth.service.AvatarStorageService;
import com.tripplanner.auth.service.UserService;
import com.tripplanner.auth.dto.response.UserProfileResponse;
import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

/**
 * 头像控制器
 *
 * <p>上传需认证（网关 /api/** 已强制 JWT）；读取端点公开（头像由 &lt;img&gt; 直连加载，
 * 不携带 Authorization，需在本服务与网关两层放行 {@code /api/auth/avatars/**}）。</p>
 */
@Tag(name = "头像管理", description = "头像上传（本地磁盘）与公开读取")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarStorageService avatarStorageService;
    private final UserService userService;

    @Operation(summary = "上传头像", description = "multipart 上传图片（≤5MB，PNG/JPEG/WebP/GIF），服务端魔数校验并生成 UUID 文件名，直接落库生效")
    @PostMapping("/me/avatar")
    public ResponseEntity<ApiResponse<UserProfileResponse>> uploadAvatar(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @RequestParam("file") MultipartFile file) {
        if (auth == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("UNAUTHORIZED", "未登录"));
        }
        String avatarUrl = avatarStorageService.save(file);
        UserProfileResponse profile = userService.assignAvatar(auth.getUserId(), avatarUrl);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @Operation(summary = "读取头像文件（公开）", description = "按 UUID 文件名读取，immutable 缓存；非法文件名或不存在返回 404")
    @GetMapping("/avatars/{filename}")
    public ResponseEntity<byte[]> getAvatar(@PathVariable String filename) {
        AvatarStorageService.AvatarFile file = avatarStorageService.load(filename);
        if (file == null) {
            throw BizException.notFound("头像文件", filename);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }
}

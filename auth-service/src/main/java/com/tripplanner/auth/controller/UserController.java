package com.tripplanner.auth.controller;

import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户公开信息查询
 */
@Tag(name = "用户公开信息", description = "跨服务公开字段查询（用户名展示等）")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 批量获取用户显示名
     * 公开行程卡片作者展示用，仅返回 id -> 昵称，不暴露邮箱等敏感信息；无需登录
     */
    @Operation(summary = "批量获取用户名", description = "按用户ID批量查询显示名，仅返回昵称非空且状态有效的用户，单次最多100个")
    @GetMapping("/names")
    public ResponseEntity<ApiResponse<Map<String, String>>> getNames(
            @RequestParam("ids") List<String> ids) {
        return ResponseEntity.ok(ApiResponse.success(userService.getDisplayNames(ids)));
    }
}

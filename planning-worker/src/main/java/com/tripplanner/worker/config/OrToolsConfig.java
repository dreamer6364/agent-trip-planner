package com.tripplanner.worker.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * OR-Tools 初始化配置
 * 暂时禁用 - OR-Tools 需要额外配置本地仓库
 * 当前使用启发式求解器作为替代
 */
@Slf4j
@Configuration
public class OrToolsConfig {

    // OR-Tools 暂时不可用
    // 需要添加 OR-Tools Maven 仓库后启用
}

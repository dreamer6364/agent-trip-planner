package com.tripplanner.trip.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 导出响应
 */
@Data
@Builder
public class ExportResponse {

    // 文件下载 URL (临时签名 URL 或直接下载端点)
    private String downloadUrl;

    // 文件名
    private String filename;

    // 文件大小 (字节)
    private long fileSize;

    // 内容类型
    private String contentType;

    // 过期时间 (如果是临时 URL)
    private String expiresAt;
}
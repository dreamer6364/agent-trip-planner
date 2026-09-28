package com.tripplanner.trip.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行程封面图搜索结果
 *
 * <p>由公开图片搜索引擎按关键词返回可浏览器直出的图片直链，
 * 供行程卡片（首页「我的行程」）展示封面使用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoverResponse {

    /** 搜索关键词（已归一化，如「杭州 西湖 雷峰塔」） */
    private String keyword;

    /** 图片直链（https） */
    private String url;

    /** 图片标题（来自搜索结果） */
    private String title;

    /** 原图宽度 */
    private Integer width;

    /** 原图高度 */
    private Integer height;

    /** 图片来源标识 */
    private String source;
}

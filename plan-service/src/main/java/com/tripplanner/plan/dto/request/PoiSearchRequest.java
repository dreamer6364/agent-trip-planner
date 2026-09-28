package com.tripplanner.plan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POI 关键词搜索请求（换景点搜索框）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PoiSearchRequest {

    /**
     * 搜索关键词（如「宋城」「中国丝绸博物馆」）
     */
    @NotBlank(message = "搜索关键词不能为空")
    @Size(max = 50, message = "关键词最多50字")
    private String keyword;

    /**
     * 限定城市（行程城市，可空）
     */
    private String city;

    /**
     * 返回条数上限（缺省 6）
     */
    private Integer limit = 6;
}

package com.tripplanner.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 响应元数据（分页、版本等）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Meta implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前页码（从 1 开始）
     */
    private Integer page;

    /**
     * 每页大小
     */
    private Integer size;

    /**
     * 总记录数
     */
    private Long total;

    /**
     * 总页数
     */
    private Integer totalPages;

    /**
     * 版本号（用于行程版本控制）
     */
    private Integer version;

    /**
     * 是否有下一页
     */
    public boolean hasNext() {
        return page != null && totalPages != null && page < totalPages;
    }

    /**
     * 是否有上一页
     */
    public boolean hasPrevious() {
        return page != null && page > 1;
    }
}
package com.aidevplatform.common.core.page;

import java.util.List;

/**
 * 分页结果（偏移分页）。
 *
 * @param total 总记录数
 * @param page  当前页码（0-based）
 * @param size  每页大小
 * @param items 当前页数据列表
 * @param <T>   数据类型
 */
public record PageResult<T>(
        long total,
        int page,
        int size,
        List<T> items
) {
    /**
     * 创建分页结果。
     */
    public static <T> PageResult<T> of(long total, int page, int size, List<T> items) {
        return new PageResult<>(total, page, size, items);
    }

    /**
     * 空结果。
     */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(0, 0, 20, List.of());
    }

    /**
     * 总页数。
     */
    public int totalPages() {
        return size > 0 ? (int) Math.ceil((double) total / size) : 0;
    }

    /**
     * 是否有下一页。
     */
    public boolean hasNext() {
        return page + 1 < totalPages();
    }
}

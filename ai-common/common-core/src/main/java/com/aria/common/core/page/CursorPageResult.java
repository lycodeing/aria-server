package com.aria.common.core.page;

import java.util.List;

/**
 * 游标分页结果（适用于大数据量滚动）。
 *
 * @param items      当前页数据
 * @param nextCursor 下一页游标（null 表示无更多数据）
 * @param hasMore    是否有更多数据
 * @param <T>        数据类型
 */
public record CursorPageResult<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore
) {
    public static <T> CursorPageResult<T> of(List<T> items, String nextCursor) {
        return new CursorPageResult<>(items, nextCursor, nextCursor != null);
    }

    public static <T> CursorPageResult<T> empty() {
        return new CursorPageResult<>(List.of(), null, false);
    }
}

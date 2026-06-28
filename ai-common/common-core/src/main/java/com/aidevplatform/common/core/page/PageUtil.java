package com.aidevplatform.common.core.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分页工具类。
 *
 * <p>统一处理 {@link PageQuery} → MyBatis-Plus {@link Page} 和
 * MyBatis-Plus {@link Page} → {@link PageResult} 的双向转换，
 * 消除各 RepositoryImpl 中重复的分页转换代码。
 *
 * <p>约定：{@link PageQuery#getPage()} 为 0-based 页码索引；
 * MyBatis-Plus {@link Page} 页码从 1 开始，转换时自动加 1。
 *
 * <p>使用示例：
 * <pre>
 * Page&lt;UserDO&gt; result = mapper.selectPage(PageUtil.toMpPage(query), wrapper);
 * return PageUtil.toPageResult(result, assembler::toDomain, query);
 * </pre>
 *
 * @author aidevplatform
 */
public final class PageUtil {

    private PageUtil() {}

    /**
     * 将 {@link PageQuery} 转换为 MyBatis-Plus {@link Page}。
     * 使用 {@link PageQuery#safePage()} 和 {@link PageQuery#safeSize()} 保证参数合法。
     *
     * @param query 分页查询对象
     * @param <T>   MyBatis-Plus 实体类型
     * @return MyBatis-Plus Page 对象（页码转为 1-based）
     */
    public static <T> Page<T> toMpPage(PageQuery query) {
        return new Page<>(query.safePage() + 1L, query.safeSize());
    }

    /**
     * 将 MyBatis-Plus {@link Page} 转换为 {@link PageResult}，同时完成类型转换。
     *
     * @param mpPage  MyBatis-Plus 查询结果
     * @param mapper  DO → Domain/VO 转换函数
     * @param query   原始分页查询（用于还原 0-based 页码）
     * @param <DO>    数据库对象类型
     * @param <R>     目标类型
     * @return 分页结果
     */
    public static <DO, R> PageResult<R> toPageResult(Page<DO> mpPage,
                                                      Function<DO, R> mapper,
                                                      PageQuery query) {
        List<R> items = mpPage.getRecords().stream()
                .map(mapper)
                .collect(Collectors.toList());
        return PageResult.of(mpPage.getTotal(), query.safePage(), query.safeSize(), items);
    }

    /**
     * 将 MyBatis-Plus {@link Page} 转换为 {@link PageResult}（类型相同，无需转换函数）。
     * 返回防御性拷贝列表，避免外部修改影响 MP 内部状态。
     *
     * @param mpPage MyBatis-Plus 查询结果
     * @param query  原始分页查询（用于还原 0-based 页码）
     * @param <T>    数据类型
     * @return 分页结果
     */
    public static <T> PageResult<T> toPageResult(Page<T> mpPage, PageQuery query) {
        return PageResult.of(mpPage.getTotal(), query.safePage(), query.safeSize(),
                new ArrayList<>(mpPage.getRecords()));
    }
}

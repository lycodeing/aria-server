package com.aria.common.core.page;

import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询基类。
 *
 * <p>所有分页查询对象均应继承此类，统一 page/size 字段命名与默认值。
 * page 为 0-based 页码索引，size 上限 200（平台硬限制，防止超大查询）。
 * 业务查询条件在子类中扩展。
 *
 * <p>使用示例：
 * <pre>
 * public class DocPageQuery extends PageQuery {
 *     private String keyword;
 *     private DocStatus status;
 * }
 * </pre>
 *
 * @author aria
 */
@Data
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码索引（0-based，默认 0） */
    private int page = 0;

    /** 每页大小（默认 20，平台硬限制上限 200） */
    private int size = 20;

    /**
     * 获取合法页码（最小为 0）。
     */
    public int safePage() {
        return Math.max(page, 0);
    }

    /**
     * 获取合法每页大小（1~200）。
     * 200 为平台硬限制，防止超大查询打垮数据库。
     */
    public int safeSize() {
        return Math.min(Math.max(size, 1), 200);
    }
}

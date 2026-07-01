package com.aria.knowledge.application.query;

import com.aria.common.core.page.PageQuery;
import com.aria.knowledge.domain.model.DocStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档分页查询条件对象。
 * 继承 {@link PageQuery} 获得 page/size 字段，扩展业务过滤条件。
 * status 使用枚举类型，在接口层转换，防止脏字符串透传到持久化层。
 *
 * @author aria
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocPageQuery extends PageQuery {

    /** 文件名关键词（null 时不过滤） */
    private String keyword;

    /** 知识库 ID（null 时查全部） */
    private String kbId;

    /** 文档状态（null 时查全部） */
    private DocStatus status;
}

package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 问题标签分布数据项 VO。
 *
 * <p>用于前端玫瑰图展示会话按问题分类（投诉/退款/咨询等）的分布。
 *
 * @author aria
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagDistributionItemVO {

    /** 标签名称（投诉/退款/咨询等），null 归类为"未分类" */
    private String tag;

    /** 该标签的会话数量 */
    private long count;
}

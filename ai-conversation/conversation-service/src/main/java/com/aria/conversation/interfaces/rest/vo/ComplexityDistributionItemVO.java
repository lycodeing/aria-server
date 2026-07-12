package com.aria.conversation.interfaces.rest.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话复杂度分布数据项 VO。
 *
 * <p>以每条会话的消息数作为复杂度度量，分三档（阈值由 system_config 控制）：
 * <ul>
 *   <li>简单（SIMPLE）：消息数 ≤ complexity.simpleMaxMessages</li>
 *   <li>中等（MEDIUM）：消息数 ≤ complexity.mediumMaxMessages</li>
 *   <li>复杂（COMPLEX）：消息数超出 mediumMaxMessages</li>
 * </ul>
 * 用于前端饼图/环形图展示复杂度分布。
 *
 * @author aria
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComplexityDistributionItemVO {

    /** 复杂度档位标签：SIMPLE / MEDIUM / COMPLEX */
    private String complexity;

    /** 该档位的会话数量（int 确保 JSON 序列化为数字而非字符串） */
    @JsonProperty("count")
    private int count;
}

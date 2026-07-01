package com.aria.knowledge.interfaces.rest.vo;

import lombok.Builder;
import lombok.Data;

/** 文档状态查询响应 VO */
@Data
@Builder
public class DocStatusVO {
    private String docId;
    private String status;
    private String fileName;
    private String message;
}

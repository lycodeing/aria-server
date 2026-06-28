package com.aidevplatform.knowledge.interfaces.rest.vo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 文档列表条目 VO（分页查询响应）。
 */
@Data
@Builder
public class DocListVO {

    private String  docId;
    private String  kbId;
    private String  fileName;
    private String  fileType;
    private String  status;
    private String  version;
    private String  uploaderId;
    private String  reviewerId;
    private Instant createdAt;
    private Instant updatedAt;
}

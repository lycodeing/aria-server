package com.aria.knowledge.interfaces.rest.vo;

import lombok.Builder;
import lombok.Data;

/** 文档上传响应 VO */
@Data
@Builder
public class DocUploadVO {
    private String docId;
    private String status;
    private String message;
}

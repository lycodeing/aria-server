package com.aidevplatform.knowledge.interfaces.rest;

import com.aidevplatform.common.core.page.PageResult;
import com.aidevplatform.common.web.response.R;
import com.aidevplatform.knowledge.application.query.DocPageQuery;
import com.aidevplatform.knowledge.application.service.DocIngestAppService;
import com.aidevplatform.knowledge.domain.model.DocStatus;
import com.aidevplatform.knowledge.domain.model.KnowledgeDoc;
import com.aidevplatform.knowledge.interfaces.rest.vo.DocListVO;
import com.aidevplatform.knowledge.interfaces.rest.vo.DocStatusVO;
import com.aidevplatform.knowledge.interfaces.rest.vo.DocUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档管理接口。
 * 所有响应统一使用 R&lt;T&gt; 包装，与平台其他服务保持一致。
 *
 * @author aidevplatform
 */
@RestController
@RequestMapping("/api/knowledge/docs")
@RequiredArgsConstructor
@Tag(name = "文档管理", description = "知识库文档上传、列表查询、审核、下线")
public class KnowledgeDocController {

    private final DocIngestAppService ingestAppService;

    @Operation(summary = "分页查询文档列表")
    @GetMapping
    public R<PageResult<DocListVO>> list(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "kbId", required = false) String kbId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        // interfaces 层负责 String → 枚举的转换和校验，不把脏字符串透传到 Application 层
        DocStatus docStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                docStatus = DocStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                return R.fail(400, "无效的文档状态值：" + status);
            }
        }
        DocPageQuery query = new DocPageQuery();
        query.setKeyword(keyword);
        query.setKbId(kbId);
        query.setStatus(docStatus);
        query.setPage(page);
        query.setSize(size);
        PageResult<KnowledgeDoc> result = ingestAppService.listDocs(query);
        List<DocListVO> items = result.items().stream().map(this::toListVO).toList();
        // 使用 result 中已经过 safePage/safeSize 处理的元数据，保证响应与实际查询一致
        return R.ok(PageResult.of(result.total(), result.page(), result.size(), items));
    }

    @Operation(summary = "查询文档摄取进度")
    @GetMapping("/{docId}/status")
    public R<DocStatusVO> status(@PathVariable("docId") String docId) {
        return R.ok(ingestAppService.getStatus(docId));
    }

    @Operation(summary = "上传文档（异步处理，立即返回 202 + docId）")
    @ApiResponse(responseCode = "200", description = "文档已接收，后台处理中")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DocUploadVO> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "kbId") String kbId) {
        return R.ok(ingestAppService.submit(file, kbId));
    }

    @Operation(summary = "审核文档（通过 → PUBLISHED，退回 → DRAFT）")
    @PutMapping("/{docId}/review")
    public R<Void> review(@PathVariable("docId") String docId, @RequestBody ReviewRequest req) {
        ingestAppService.review(docId, req.isApproved(), req.getRejectReason());
        return R.ok();
    }

    @Operation(summary = "下线文档（物理删除所有 chunk，不可恢复）")
    @DeleteMapping("/{docId}")
    public R<Void> offline(@PathVariable("docId") String docId) {
        ingestAppService.offline(docId);
        return R.ok();
    }

    private DocListVO toListVO(KnowledgeDoc doc) {
        return DocListVO.builder()
            .docId(doc.getId())
            .kbId(doc.getKbId())
            .fileName(doc.getFileName())
            .fileType(doc.getFileType())
            .status(doc.getStatus().name())
            .version(doc.getVersion())
            .uploaderId(doc.getUploaderId())
            .reviewerId(doc.getReviewerId())
            .createdAt(doc.getCreatedAt())
            .updatedAt(doc.getUpdatedAt())
            .build();
    }

    @Data
    public static class ReviewRequest {
        @NotNull
        private boolean approved;
        private String rejectReason;
    }
}

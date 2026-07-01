package com.aria.knowledge.interfaces.rest;

import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import com.aria.knowledge.application.query.DocPageQuery;
import com.aria.knowledge.application.service.DocIngestAppService;
import com.aria.knowledge.application.service.KnowledgeSearchAppService;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeDoc;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.storage.MinioStorageService;
import com.aria.knowledge.interfaces.rest.vo.DocListVO;
import com.aria.knowledge.interfaces.rest.vo.DocStatusVO;
import com.aria.knowledge.interfaces.rest.vo.DocUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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
 * @author aria
 */
@RestController
@RequestMapping("/api/knowledge/docs")
@RequiredArgsConstructor
@Tag(name = "文档管理", description = "知识库文档上传、列表查询、审核、下线")
public class KnowledgeDocController {

    private final DocIngestAppService      ingestAppService;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeSearchAppService searchAppService;
    private final KnowledgeDocRepository   docRepository;
    private final MinioStorageService      minioStorageService;

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

    @Operation(summary = "预览文档原文（PDF/HTML/Markdown 直接输出字节流，前端用 iframe 渲染）")
    @GetMapping("/{docId}/preview")
    public void preview(@PathVariable("docId") String docId, HttpServletResponse response) throws java.io.IOException {
        KnowledgeDoc doc = docRepository.findById(docId)
            .orElseThrow(() -> new com.aria.common.core.exception.BusinessException(4004, "文档不存在：" + docId));
        byte[] bytes = minioStorageService.download(doc.getStoragePath());
        String contentType = resolvePreviewContentType(doc.getFileType());
        response.setContentType(contentType);
        response.setContentLength(bytes.length);
        // 内联显示（非 attachment），让浏览器直接渲染 PDF
        response.setHeader("Content-Disposition", "inline; filename=\"" + doc.getFileName() + "\"");
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    private String resolvePreviewContentType(String fileType) {
        if (fileType == null) return "application/octet-stream";
        return switch (fileType.toUpperCase()) {
            case "PDF"      -> "application/pdf";
            case "HTML"     -> "text/html; charset=UTF-8";
            case "MARKDOWN" -> "text/plain; charset=UTF-8";
            case "DOCX"     -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default         -> "application/octet-stream";
        };
    }

    @Operation(summary = "查询文档的所有 chunk 解析详情（页码、章节、类型、内容）")
    @GetMapping("/{docId}/chunks")
    public R<List<ChunkVO>> chunks(@PathVariable("docId") String docId) {
        List<KnowledgeChunk> chunks = chunkRepository.findByDocId(docId);
        List<ChunkVO> vos = chunks.stream().map(c -> {
            ChunkVO vo = new ChunkVO();
            vo.chunkId    = c.getId();
            vo.pageNum    = c.getPageNum();
            vo.sectionTitle = c.getSectionTitle();
            vo.chunkType  = c.getChunkType() != null ? c.getChunkType().name() : "TEXT";
            vo.tokenCount = c.getTokenCount();
            vo.content    = c.getContent();
            return vo;
        }).toList();
        return R.ok(vos);
    }

    /** Chunk 详情 VO（仅用于此接口，不含向量字段） */
    @Data
    public static class ChunkVO {
        public String  chunkId;
        public Integer pageNum;
        public String  sectionTitle;
        public String  chunkType;
        public Integer tokenCount;
        public String  content;
        public Double  retrievalWeight;
    }

    @Operation(summary = "检索测试（管理后台，返回命中 chunk 列表+分数+来源+文档元数据）")
    @PostMapping("/search-test")
    public R<List<SearchHitVO>> searchTest(@RequestBody SearchTestRequest req) {
        var hits = searchAppService.managementSearch(req.getQuery(), req.getKbId(), req.getTopK());
        // 批量查 docId → fileName，避免 N+1
        java.util.Map<String, String> fileNameMap = hits.stream()
            .map(h -> h.getDocId())
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .collect(java.util.stream.Collectors.toMap(
                id -> id,
                id -> docRepository.findById(id).map(d -> d.getFileName()).orElse(""),
                (a, b) -> a
            ));
        List<SearchHitVO> vos = hits.stream().map(h -> {
            SearchHitVO vo = new SearchHitVO();
            vo.chunkId      = h.getChunkId();
            vo.docId        = h.getDocId() != null ? h.getDocId() : "";
            vo.kbId         = h.getKbId() != null ? h.getKbId() : req.getKbId();
            vo.fileName     = fileNameMap.getOrDefault(vo.docId, "");
            vo.content      = h.getContent();
            vo.score        = h.getScore();
            vo.source       = h.getSource() != null ? h.getSource().name() : "VECTOR";
            vo.pageNum      = h.getPageNum();
            vo.sectionTitle = h.getSectionTitle();
            vo.chunkType    = h.getChunkType() != null ? h.getChunkType() : "TEXT";
            return vo;
        }).toList();
        return R.ok(vos);
    }

    @Operation(summary = "失败文档重试（FAILED → DRAFT → 重新摄取）")
    @PostMapping("/{docId}/retry")
    public R<Void> retry(@PathVariable("docId") String docId) {
        ingestAppService.retry(docId);
        return R.ok();
    }

    @Operation(summary = "已发布文档重新摄取（不改状态，幂等重跑 pipeline）")
    @PostMapping("/{docId}/reingest")
    public R<Void> reingest(@PathVariable("docId") String docId) {
        ingestAppService.reingest(docId);
        return R.ok();
    }

    @Operation(summary = "查询文档 chunk 统计（总数、各类型数量、总 token）")
    @GetMapping("/{docId}/stats")
    public R<DocStatsVO> stats(@PathVariable("docId") String docId) {
        List<KnowledgeChunk> chunks = chunkRepository.findByDocId(docId);
        DocStatsVO vo = new DocStatsVO();
        vo.totalChunks = chunks.size();
        vo.totalTokens = chunks.stream()
            .mapToInt(c -> c.getTokenCount() != null ? c.getTokenCount() : 0).sum();
        vo.textChunks  = (int) chunks.stream()
            .filter(c -> c.getChunkType() == null || "TEXT".equals(c.getChunkType().name())).count();
        vo.tableChunks = (int) chunks.stream()
            .filter(c -> c.getChunkType() != null && "TABLE".equals(c.getChunkType().name())).count();
        vo.imageChunks = (int) chunks.stream()
            .filter(c -> c.getChunkType() != null && "IMAGE_CAPTION".equals(c.getChunkType().name())).count();
        return R.ok(vo);
    }

    @Data public static class SearchTestRequest {
        @NotBlank private String query;
        @NotBlank private String kbId;
        @Min(1) @Max(20) private int topK = 5;
    }

    @Data public static class SearchHitVO {
        public String  chunkId;
        public String  docId;
        public String  kbId;
        public String  fileName;
        public String  content;
        public double  score;
        public String  source;
        public Integer pageNum;
        public String  sectionTitle;
        public String  chunkType;
    }

    @Data public static class DocStatsVO {
        public int totalChunks;
        public int totalTokens;
        public int textChunks;
        public int tableChunks;
        public int imageChunks;
    }

    @Operation(summary = "批量下线文档（最多 50 条，非 PUBLISHED 状态自动跳过）")
    @PostMapping("/batch-offline")
    public R<Void> batchOffline(@RequestBody BatchOfflineRequest req) {
        ingestAppService.batchOffline(req.getDocIds());
        return R.ok();
    }

    @Operation(summary = "查询指定知识库的 chunk/token 汇总统计")
    @GetMapping("/kb-stats")
    public R<java.util.Map<String, Object>> kbStats(@RequestParam("kbId") String kbId) {
        var docQuery = new com.aria.knowledge.application.query.DocPageQuery();
        docQuery.setKbId(kbId);
        docQuery.setStatus(DocStatus.PUBLISHED);
        docQuery.setPage(0);
        docQuery.setSize(1);
        long docCount = ingestAppService.listDocs(docQuery).total();
        var chunkStats = chunkRepository.countStatsByKbId(kbId);
        return R.ok(java.util.Map.of(
            "kbId",       kbId,
            "docCount",   docCount,
            "chunkCount", chunkStats.get("chunkCount"),
            "tokenSum",   chunkStats.get("tokenSum")
        ));
    }

    @Data public static class BatchOfflineRequest {
        private java.util.List<String> docIds;
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

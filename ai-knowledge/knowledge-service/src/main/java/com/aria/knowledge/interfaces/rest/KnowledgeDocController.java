package com.aria.knowledge.interfaces.rest;

import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import com.aria.knowledge.application.query.DocPageQuery;
import com.aria.knowledge.application.service.DocIngestAppService;
import com.aria.knowledge.application.service.KnowledgeDocQueryAppService;
import com.aria.knowledge.application.service.KnowledgeSearchAppService;
import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.domain.model.KnowledgeDoc;
import com.aria.knowledge.interfaces.rest.vo.DocListVO;
import com.aria.knowledge.interfaces.rest.vo.DocStatusVO;
import com.aria.knowledge.interfaces.rest.vo.DocUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档管理接口。
 *
 * <p>DDD 分层职责：Interface 层只做入参格式校验和 VO 转换，
 * 所有业务逻辑委托给 Application 层（{@link DocIngestAppService}、
 * {@link KnowledgeDocQueryAppService}、{@link KnowledgeSearchAppService}）。
 */
@RestController
@RequestMapping("/api/knowledge/docs")
@RequiredArgsConstructor
@Tag(name = "文档管理", description = "知识库文档上传、列表查询、审核、下线")
public class KnowledgeDocController {

    private final DocIngestAppService          ingestAppService;
    private final KnowledgeDocQueryAppService  queryAppService;
    private final KnowledgeSearchAppService    searchAppService;

    // ---- 分页查询 ----

    @Operation(summary = "分页查询文档列表")
    @GetMapping
    public R<PageResult<DocListVO>> list(DocPageQuery query) {
        // keyword/kbId/status/page/size 全部由 Spring MVC 自动绑定
        // status 枚举值非法时抛 BindException，由 GlobalExceptionHandler 统一返回 400
        PageResult<KnowledgeDoc> result = ingestAppService.listDocs(query);
        List<DocListVO> items = result.items().stream().map(this::toListVO).toList();
        return R.ok(PageResult.of(result.total(), result.page(), result.size(), items));
    }

    // ---- 文档状态 ----

    @Operation(summary = "查询文档摄取进度")
    @GetMapping("/{docId}/status")
    public R<DocStatusVO> status(@PathVariable("docId") String docId) {
        return R.ok(ingestAppService.getStatus(docId));
    }

    // ---- 上传 ----

    @Operation(summary = "上传文档（异步处理，立即返回 202 + docId）")
    @ApiResponse(responseCode = "200", description = "文档已接收，后台处理中")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DocUploadVO> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "kbId") String kbId) {
        return R.ok(ingestAppService.submit(file, kbId));
    }

    // ---- 审核 / 下线 / 重试 ----

    @Operation(summary = "审核文档（通过 → PUBLISHED，退回 → DRAFT）")
    @PutMapping("/{docId}/review")
    public R<Void> review(@PathVariable("docId") String docId,
                          @RequestBody @Valid ReviewRequest req) {
        ingestAppService.review(docId, req.isApproved(), req.getRejectReason());
        return R.ok();
    }

    @Operation(summary = "下线文档（物理删除所有 chunk，不可恢复）")
    @DeleteMapping("/{docId}")
    public R<Void> offline(@PathVariable("docId") String docId) {
        ingestAppService.offline(docId);
        return R.ok();
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

    @Operation(summary = "批量下线文档（最多 50 条，非 PUBLISHED 状态自动跳过）")
    @PostMapping("/batch-offline")
    public R<Void> batchOffline(@RequestBody @Valid BatchOfflineRequest req) {
        ingestAppService.batchOffline(req.getDocIds());
        return R.ok();
    }

    // ---- 查询（委托给 KnowledgeDocQueryAppService，消除 Domain/Infra 直接依赖）----

    @Operation(summary = "预览文档原文（PDF/HTML/Markdown 直接输出字节流，前端用 iframe 渲染）")
    @GetMapping("/{docId}/preview")
    public void preview(@PathVariable("docId") String docId,
                        HttpServletResponse response) throws IOException {
        KnowledgeDocQueryAppService.DocPreviewResult result = queryAppService.getPreview(docId);
        response.setContentType(resolvePreviewContentType(result.fileType()));
        response.setContentLength(result.bytes().length);
        // 文件名转义：移除 CR/LF/双引号/反斜杠，防止 HTTP Response Splitting 注入
        String safeName = result.fileName() != null
                ? result.fileName().replaceAll("[\\r\\n\"\\\\]", "_") : "document";
        response.setHeader("Content-Disposition", "inline; filename=\"" + safeName + "\"");
        response.getOutputStream().write(result.bytes());
        response.getOutputStream().flush();
    }

    @Operation(summary = "查询文档的所有 chunk 解析详情（页码、章节、类型、内容）")
    @GetMapping("/{docId}/chunks")
    public R<List<ChunkVO>> chunks(@PathVariable("docId") String docId) {
        List<KnowledgeChunk> chunks = queryAppService.getChunks(docId);
        List<ChunkVO> vos = chunks.stream().map(c -> {
            ChunkVO vo = new ChunkVO();
            vo.setChunkId(c.getId());
            vo.setPageNum(c.getPageNum());
            vo.setSectionTitle(c.getSectionTitle());
            vo.setChunkType(c.getChunkType() != null ? c.getChunkType().name() : "TEXT");
            vo.setTokenCount(c.getTokenCount());
            vo.setContent(c.getContent());
            return vo;
        }).toList();
        return R.ok(vos);
    }

    @Operation(summary = "查询文档 chunk 统计（总数、各类型数量、总 token）")
    @GetMapping("/{docId}/stats")
    public R<DocStatsVO> stats(@PathVariable("docId") String docId) {
        var stats = queryAppService.getDocStats(docId);
        DocStatsVO vo = new DocStatsVO();
        vo.setTotalChunks((int) stats.totalChunks());
        vo.setTotalTokens((int) stats.totalTokens());
        vo.setTextChunks((int) stats.textChunks());
        vo.setTableChunks((int) stats.tableChunks());
        vo.setImageChunks((int) stats.imageChunks());
        return R.ok(vo);
    }

    @Operation(summary = "查询指定知识库的 chunk/token 汇总统计")
    @GetMapping("/kb-stats")
    public R<KbStatsVO> kbStats(@RequestParam("kbId") String kbId) {
        DocPageQuery docQuery = new DocPageQuery();
        docQuery.setKbId(kbId);
        docQuery.setStatus(DocStatus.PUBLISHED);
        docQuery.setPage(0);
        docQuery.setSize(1);
        long docCount = ingestAppService.listDocs(docQuery).total();
        var stats = queryAppService.getKbStats(kbId);
        KbStatsVO vo = new KbStatsVO();
        vo.setKbId(kbId);
        vo.setDocCount(docCount);
        vo.setChunkCount(stats.chunkCount());
        vo.setTokenSum(stats.tokenSum());
        return R.ok(vo);
    }

    @Operation(summary = "检索测试（管理后台，返回命中 chunk 列表+分数+来源+文档元数据）")
    @PostMapping("/search-test")
    public R<List<SearchHitVO>> searchTest(@RequestBody @Valid SearchTestRequest req) {
        var hits = searchAppService.managementSearch(req.getQuery(), req.getKbId(), req.getTopK());
        List<String> docIds = hits.stream()
                .map(h -> h.getDocId())
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        // 批量查 docId → fileName，消除 N+1
        Map<String, String> fileNameMap = queryAppService.findFileNamesByIds(docIds);
        List<SearchHitVO> vos = hits.stream().map(h -> {
            SearchHitVO vo = new SearchHitVO();
            vo.setChunkId(h.getChunkId());
            vo.setDocId(h.getDocId() != null ? h.getDocId() : "");
            vo.setKbId(h.getKbId() != null ? h.getKbId() : req.getKbId());
            vo.setFileName(fileNameMap.getOrDefault(vo.getDocId(), ""));
            vo.setContent(h.getContent());
            vo.setScore(h.getScore());
            vo.setSource(h.getSource() != null ? h.getSource().name() : "VECTOR");
            vo.setPageNum(h.getPageNum());
            vo.setSectionTitle(h.getSectionTitle());
            vo.setChunkType(h.getChunkType() != null ? h.getChunkType() : "TEXT");
            return vo;
        }).toList();
        return R.ok(vos);
    }

    // ---- 私有工具方法 ----

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

    // ---- VO / Request 内部类 ----

    /** Chunk 详情 VO */
    @Data
    public static class ChunkVO {
        private String  chunkId;
        private Integer pageNum;
        private String  sectionTitle;
        private String  chunkType;
        private Integer tokenCount;
        private String  content;
    }

    @Data
    public static class SearchTestRequest {
        @NotBlank private String query;
        @NotBlank private String kbId;
        @Min(1) @Max(20) private int topK = 5;
    }

    @Data
    public static class SearchHitVO {
        private String  chunkId;
        private String  docId;
        private String  kbId;
        private String  fileName;
        private String  content;
        private double  score;
        private String  source;
        private Integer pageNum;
        private String  sectionTitle;
        private String  chunkType;
    }

    @Data
    public static class DocStatsVO {
        private int totalChunks;
        private int totalTokens;
        private int textChunks;
        private int tableChunks;
        private int imageChunks;
    }

    @Data
    public static class KbStatsVO {
        /** 知识库 ID */
        private String kbId;
        /** 已发布文档数 */
        private long docCount;
        /** chunk 总数 */
        private long chunkCount;
        /** token 总量 */
        private long tokenSum;
    }

    @Data
    public static class BatchOfflineRequest {
        @NotNull private List<String> docIds;
    }

    @Data
    public static class ReviewRequest {
        @NotNull private boolean approved;
        private String rejectReason;
    }
}

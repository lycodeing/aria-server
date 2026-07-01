package com.aria.knowledge.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.knowledge.application.service.KnowledgeChunkAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Chunk 精细管理接口（启用/禁用/编辑/Q&A 添加）。
 */
@RestController
@RequestMapping("/api/knowledge/chunks")
@RequiredArgsConstructor
@Tag(name = "Chunk 管理", description = "Chunk 启用/禁用/编辑/Q&A 添加")
public class KnowledgeChunkController {

    private final KnowledgeChunkAppService chunkAppService;

    @Operation(summary = "禁用 Chunk（retrieval_weight=0，不物理删除）")
    @PostMapping("/{chunkId}/disable")
    public R<Void> disable(@PathVariable String chunkId) {
        chunkAppService.disable(chunkId);
        return R.ok();
    }

    @Operation(summary = "启用 Chunk（retrieval_weight 恢复为 1.0）")
    @PostMapping("/{chunkId}/enable")
    public R<Void> enable(@PathVariable String chunkId) {
        chunkAppService.enable(chunkId);
        return R.ok();
    }

    @Operation(summary = "编辑 Chunk 内容并重新向量化")
    @PutMapping("/{chunkId}/content")
    public R<Void> updateContent(
            @PathVariable String chunkId,
            @RequestBody UpdateContentRequest req) {
        chunkAppService.updateContent(chunkId, req.getContent());
        return R.ok();
    }

    @Operation(summary = "手动添加 Q&A Chunk（问答对入库并向量化）")
    @PostMapping("/qa")
    public R<Void> addQA(@RequestBody AddQARequest req) {
        chunkAppService.addQA(req.getDocId(), req.getKbId(),
            req.getQuestion(), req.getAnswer());
        return R.ok();
    }

    @Data
    public static class UpdateContentRequest {
        @NotBlank private String content;
    }

    @Data
    public static class AddQARequest {
        @NotBlank private String docId;
        @NotBlank private String kbId;
        @NotBlank private String question;
        @NotBlank private String answer;
    }
}

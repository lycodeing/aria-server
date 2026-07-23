package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.web.response.R;
import com.aria.conversation.infrastructure.persistence.entity.TagEntity;
import com.aria.conversation.infrastructure.persistence.mapper.TagMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
public class TagAdminController {

    private static final int CONFLICT  = 40900;
    private static final int NOT_FOUND = 40400;

    private final TagMapper tagMapper;

    @GetMapping
    @SaCheckPermission("system:tag:manage")
    public R<List<TagEntity>> list(
            @RequestParam(required = false) String source) {
        var wrapper = Wrappers.<TagEntity>lambdaQuery();
        if (source != null) wrapper.eq(TagEntity::getSource, source);
        return R.ok(tagMapper.selectList(wrapper.orderByAsc(TagEntity::getName)));
    }

    @PostMapping
    @SaCheckPermission("system:tag:manage")
    public R<TagEntity> create(@RequestBody @Validated CreateTagReq req) {
        if (tagMapper.selectByName(req.getName()) != null) {
            throw new BusinessException(CONFLICT, "标签名已存在: " + req.getName());
        }
        TagEntity entity = TagEntity.builder()
                .name(req.getName()).color(req.getColor())
                .source("PRESET").build();
        tagMapper.insert(entity);
        return R.ok(entity);
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:tag:manage")
    public R<Void> update(@PathVariable Long id,
                           @RequestBody @Validated UpdateTagReq req) {
        TagEntity existing = tagMapper.selectById(id);
        if (existing == null) throw new BusinessException(NOT_FOUND, "标签不存在");
        existing.setName(req.getName());
        existing.setColor(req.getColor());
        existing.setSource(req.getSource());
        tagMapper.updateById(existing);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:tag:manage")
    public R<Void> delete(@PathVariable Long id) {
        // usage_count > 0 时前端已二次确认，后端直接执行
        tagMapper.deleteById(id);
        return R.ok();
    }

    @Data
    public static class CreateTagReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String color;
    }

    @Data
    public static class UpdateTagReq {
        @NotBlank @Size(max = 50) private String name;
        @NotBlank private String color;
        @NotBlank private String source;
    }
}

package com.aidevplatform.knowledge.infrastructure.persistence.repository;

import com.aidevplatform.common.core.page.PageResult;
import com.aidevplatform.common.core.page.PageUtil;
import com.aidevplatform.knowledge.application.query.DocPageQuery;
import com.aidevplatform.knowledge.domain.model.DocStatus;
import com.aidevplatform.knowledge.domain.model.KnowledgeDoc;
import com.aidevplatform.knowledge.domain.repository.KnowledgeDocRepository;
import com.aidevplatform.knowledge.infrastructure.persistence.assembler.KnowledgeDocAssembler;
import com.aidevplatform.knowledge.infrastructure.persistence.entity.KnowledgeDocEntity;
import com.aidevplatform.knowledge.infrastructure.persistence.mapper.KnowledgeDocMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 文档 Repository 实现（基础设施层）。
 * 使用 {@link PageUtil} 统一处理分页转换，消除重复的 MP Page 操作代码。
 *
 * @author aidevplatform
 */
@Repository
@RequiredArgsConstructor
public class KnowledgeDocRepositoryImpl implements KnowledgeDocRepository {

    private final KnowledgeDocMapper    docMapper;
    private final KnowledgeDocAssembler assembler;

    @Override
    public void save(KnowledgeDoc doc) {
        docMapper.insert(assembler.toEntity(doc));
    }

    @Override
    public Optional<KnowledgeDoc> findById(String docId) {
        return Optional.ofNullable(docMapper.selectById(docId))
            .map(assembler::toDomain);
    }

    @Override
    public List<KnowledgeDoc> findExpired(LocalDate today) {
        return docMapper.selectList(new LambdaQueryWrapper<KnowledgeDocEntity>()
            .lt(KnowledgeDocEntity::getExpiresAt, today)
            .ne(KnowledgeDocEntity::getStatus, DocStatus.DEPRECATED.name())
        ).stream().map(assembler::toDomain).toList();
    }

    @Override
    public PageResult<KnowledgeDoc> findPage(DocPageQuery query) {
        LambdaQueryWrapper<KnowledgeDocEntity> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            qw.like(KnowledgeDocEntity::getFileName, query.getKeyword().trim());
        }
        if (StringUtils.hasText(query.getKbId())) {
            qw.eq(KnowledgeDocEntity::getKbId, query.getKbId());
        }
        // status 已是强类型枚举，直接取 name() 写入 SQL，无脏字符串风险
        if (query.getStatus() != null) {
            qw.eq(KnowledgeDocEntity::getStatus, query.getStatus().name());
        }
        qw.orderByDesc(KnowledgeDocEntity::getCreatedAt);

        Page<KnowledgeDocEntity> result = docMapper.selectPage(PageUtil.toMpPage(query), qw);
        return PageUtil.toPageResult(result, assembler::toDomain, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateReview(String docId, DocStatus status, String reviewerId) {
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocEntity>()
            .eq(KnowledgeDocEntity::getId, docId)
            .set(KnowledgeDocEntity::getStatus, status.name())
            .set(KnowledgeDocEntity::getReviewerId, reviewerId)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusBatch(List<String> docIds, DocStatus status) {
        if (CollectionUtils.isEmpty(docIds)) {
            return;
        }
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocEntity>()
            .in(KnowledgeDocEntity::getId, docIds)
            .set(KnowledgeDocEntity::getStatus, status.name())
        );
    }
}

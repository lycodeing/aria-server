package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.canned.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CannedResponseAppService {

    private static final int NOT_FOUND = 40400;
    private static final int FORBIDDEN  = 40300;

    private final CannedResponseGroupMapper groupMapper;
    private final CannedResponseMapper cannedMapper;

    // ── 分组 ──────────────────────────────────────────────

    public List<CannedResponseGroupDO> listGroups() {
        return groupMapper.selectAllActive();
    }

    @Transactional(rollbackFor = Exception.class)
    public CannedResponseGroupDO createGroup(String name, Long parentId,
                                              int sortOrder, Long createdBy) {
        CannedResponseGroupDO g = new CannedResponseGroupDO();
        g.setName(name); g.setParentId(parentId);
        g.setSortOrder(sortOrder); g.setCreatedBy(createdBy);
        g.setCreatedAt(OffsetDateTime.now()); g.setDeleted(false);
        groupMapper.insert(g);
        return g;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(Long id, String name, Long parentId, int sortOrder) {
        CannedResponseGroupDO g = requireGroup(id);
        g.setName(name); g.setParentId(parentId); g.setSortOrder(sortOrder);
        groupMapper.updateById(g);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long id) {
        requireGroup(id);
        long childCount = groupMapper.selectCount(Wrappers.lambdaQuery(CannedResponseGroupDO.class)
                .eq(CannedResponseGroupDO::getParentId, id)
                .eq(CannedResponseGroupDO::getDeleted, false));
        if (childCount > 0) {
            throw new BusinessException(40001, "该分组下存在子分组，请先删除子分组");
        }
        long crCount = cannedMapper.selectCount(Wrappers.lambdaQuery(CannedResponseDO.class)
                .eq(CannedResponseDO::getGroupId, id)
                .eq(CannedResponseDO::getDeleted, false));
        if (crCount > 0) {
            throw new BusinessException(40001, "该分组下存在快捷回复，请先删除或移出");
        }
        groupMapper.update(Wrappers.lambdaUpdate(CannedResponseGroupDO.class)
                .set(CannedResponseGroupDO::getDeleted, true)
                .eq(CannedResponseGroupDO::getId, id));
    }

    // ── 公共快捷回复（管理员） ─────────────────────────────

    public List<CannedResponseDO> listPublic(Long groupId, int page, int size) {
        return cannedMapper.selectList(Wrappers.lambdaQuery(CannedResponseDO.class)
                .eq(CannedResponseDO::getScope, "PUBLIC")
                .eq(CannedResponseDO::getDeleted, false)
                .eq(groupId != null, CannedResponseDO::getGroupId, groupId)
                .orderByAsc(CannedResponseDO::getSortOrder)
                .last("LIMIT " + size + " OFFSET " + (long)(page - 1) * size));
    }

    @Transactional(rollbackFor = Exception.class)
    public CannedResponseDO createPublic(String title, String content,
                                          Long groupId, int sortOrder, Long createdBy) {
        CannedResponseDO cr = buildCr(title, content, groupId, sortOrder, createdBy);
        cr.setScope("PUBLIC");
        cannedMapper.insert(cr);
        return cr;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePublic(Long id, String title, String content,
                              Long groupId, int sortOrder) {
        CannedResponseDO cr = requireCr(id);
        cr.setTitle(title); cr.setContent(content);
        cr.setGroupId(groupId); cr.setSortOrder(sortOrder);
        cr.setUpdatedAt(OffsetDateTime.now());
        cannedMapper.updateById(cr);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePublic(Long id) {
        requireCr(id);
        softDelete(id);
    }

    // ── 私人快捷回复（坐席自己） ──────────────────────────

    public List<CannedResponseDO> listPrivate(Long agentId) {
        return cannedMapper.selectPrivateByAgent(agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public CannedResponseDO createPrivate(String title, String content,
                                           Long groupId, Long agentId) {
        CannedResponseDO cr = buildCr(title, content, groupId, 0, agentId);
        cr.setScope("PRIVATE"); cr.setOwnerId(agentId);
        cannedMapper.insert(cr);
        return cr;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePrivate(Long id, String title, String content, Long agentId) {
        CannedResponseDO cr = requireCr(id);
        requireOwner(cr, agentId);
        cr.setTitle(title); cr.setContent(content);
        cr.setUpdatedAt(OffsetDateTime.now());
        cannedMapper.updateById(cr);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePrivate(Long id, Long agentId) {
        CannedResponseDO cr = requireCr(id);
        requireOwner(cr, agentId);
        softDelete(id);
    }

    // ── 搜索 ──────────────────────────────────────────────

    /**
     * 关键词搜索：空关键词时返回 use_count 最高的前 limit 条，
     * 非空时走 pg 全文检索。
     */
    public List<CannedResponseDO> search(String q, Long agentId, Long groupId, int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 30);
        if (q == null || q.isBlank()) {
            return cannedMapper.selectList(Wrappers.lambdaQuery(CannedResponseDO.class)
                    .eq(CannedResponseDO::getDeleted, false)
                    .and(w -> w.eq(CannedResponseDO::getScope, "PUBLIC")
                            .or().and(inner -> inner
                                    .eq(CannedResponseDO::getScope, "PRIVATE")
                                    .eq(CannedResponseDO::getOwnerId, agentId)))
                    .eq(groupId != null, CannedResponseDO::getGroupId, groupId)
                    .orderByDesc(CannedResponseDO::getUseCount)
                    .last("LIMIT " + safeLimit));
        }
        return cannedMapper.searchByKeyword(q.trim(), agentId, groupId, safeLimit);
    }

    /** 异步递增使用次数，不阻塞调用方 */
    @Async
    public void recordUse(Long id) {
        cannedMapper.incrementUseCount(id);
    }

    // ── 私有工具方法 ──────────────────────────────────────

    private CannedResponseGroupDO requireGroup(Long id) {
        CannedResponseGroupDO g = groupMapper.selectById(id);
        if (g == null || Boolean.TRUE.equals(g.getDeleted())) {
            throw new BusinessException(NOT_FOUND, "分组不存在: " + id);
        }
        return g;
    }

    private CannedResponseDO requireCr(Long id) {
        CannedResponseDO cr = cannedMapper.selectById(id);
        if (cr == null || Boolean.TRUE.equals(cr.getDeleted())) {
            throw new BusinessException(NOT_FOUND, "快捷回复不存在: " + id);
        }
        return cr;
    }

    private void requireOwner(CannedResponseDO cr, Long agentId) {
        if (!agentId.equals(cr.getOwnerId())) {
            throw new BusinessException(FORBIDDEN, "无权限操作他人快捷回复");
        }
    }

    private void softDelete(Long id) {
        cannedMapper.update(Wrappers.lambdaUpdate(CannedResponseDO.class)
                .set(CannedResponseDO::getDeleted, true)
                .eq(CannedResponseDO::getId, id));
    }

    private CannedResponseDO buildCr(String title, String content,
                                      Long groupId, int sortOrder, Long createdBy) {
        CannedResponseDO cr = new CannedResponseDO();
        cr.setTitle(title); cr.setContent(content);
        cr.setGroupId(groupId); cr.setSortOrder(sortOrder);
        cr.setCreatedBy(createdBy); cr.setUseCount(0);
        cr.setCreatedAt(OffsetDateTime.now());
        cr.setUpdatedAt(OffsetDateTime.now());
        cr.setDeleted(false);
        return cr;
    }
}

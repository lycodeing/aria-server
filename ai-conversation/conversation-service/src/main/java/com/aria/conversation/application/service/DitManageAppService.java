package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.dit.domain.*;
import com.aria.conversation.infrastructure.dit.mapper.*;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DIT 框架管理应用服务。
 * 领域、意图、槽位、工具的 CRUD 编排，同时维护缓存一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DitManageAppService {

    private final DomainMapper domainMapper;
    private final IntentMapper intentMapper;
    private final IntentSlotMapper slotMapper;
    private final ToolMapper toolMapper;
    private final IntentToolMapper intentToolMapper;
    private final DomainRepository domainRepository; // 用于缓存失效

    // ---- 领域 ----

    public List<DomainDO> listDomains() {
        return domainMapper.selectList(null);
    }

    public DomainDO getDomain(Long id) {
        DomainDO domain = domainMapper.selectById(id);
        if (domain == null) throw new com.aria.common.core.exception.BusinessException(4004, "领域不存在: " + id);
        return domain;
    }

    public DomainDO createDomain(DomainDO domain) {
        domainMapper.insert(domain);
        return domain;
    }

    public void updateDomain(DomainDO domain) {
        if (domainMapper.updateById(domain) == 0)
            throw new com.aria.common.core.exception.BusinessException(4004, "领域不存在: " + domain.getId());
        domainRepository.evict(getDomain(domain.getId()).getCode());
    }

    public void deleteDomain(Long id) {
        DomainDO d = getDomain(id);
        domainMapper.deleteById(id);
        domainRepository.evict(d.getCode());
    }

    // ---- 意图 ----

    public List<IntentDO> listIntents(Long domainId) {
        return intentMapper.selectList(
                new LambdaQueryWrapper<IntentDO>().eq(IntentDO::getDomainId, domainId)
                        .orderByAsc(IntentDO::getSortOrder));
    }

    public IntentDO createIntent(IntentDO intent) {
        intentMapper.insert(intent);
        evictDomainByIntentId(intent.getId());
        return intent;
    }

    public void updateIntent(IntentDO intent) {
        if (intentMapper.updateById(intent) == 0)
            throw new com.aria.common.core.exception.BusinessException(4004, "意图不存在: " + intent.getId());
        evictDomainByIntentId(intent.getId());
    }

    public void deleteIntent(Long intentId) {
        evictDomainByIntentId(intentId);  // 先失效缓存，此时记录仍在
        intentMapper.deleteById(intentId);
        // 级联删除槽位和绑定
        slotMapper.delete(new LambdaQueryWrapper<IntentSlotDO>().eq(IntentSlotDO::getIntentId, intentId));
        intentToolMapper.delete(new LambdaQueryWrapper<IntentToolDO>().eq(IntentToolDO::getIntentId, intentId));
    }

    // ---- 槽位 ----

    public List<IntentSlotDO> listSlots(Long intentId) {
        return slotMapper.findByIntentId(intentId);
    }

    public IntentSlotDO createSlot(IntentSlotDO slot) {
        slotMapper.insert(slot);
        evictDomainByIntentId(slot.getIntentId());
        return slot;
    }

    public void updateSlot(IntentSlotDO slot) {
        slotMapper.updateById(slot);
        evictDomainByIntentId(slot.getIntentId());
    }

    public void deleteSlot(Long slotId) {
        IntentSlotDO s = slotMapper.selectById(slotId);
        if (s == null) throw new com.aria.common.core.exception.BusinessException(4004, "槽位不存在: " + slotId);
        slotMapper.deleteById(slotId);
        evictDomainByIntentId(s.getIntentId());
    }

    // ---- 工具 ----

    public List<ToolDO> listTools() {
        return toolMapper.selectList(null);
    }

    public ToolDO createTool(ToolDO tool) {
        toolMapper.insert(tool);
        return tool;
    }

    public void updateTool(ToolDO tool) {
        toolMapper.updateById(tool);
    }

    public void deleteTool(Long toolId) {
        toolMapper.deleteById(toolId);
    }

    // ---- 意图-工具绑定 ----

    public List<IntentToolDO> listBindings(Long intentId) {
        return intentToolMapper.findByIntentId(intentId);
    }

    public IntentToolDO createBinding(IntentToolDO binding) {
        intentToolMapper.insert(binding);
        evictDomainByIntentId(binding.getIntentId());
        return binding;
    }

    public void deleteBinding(Long bindingId) {
        IntentToolDO b = intentToolMapper.selectById(bindingId);
        if (b == null) throw new com.aria.common.core.exception.BusinessException(4004, "绑定不存在: " + bindingId);
        intentToolMapper.deleteById(bindingId);
        evictDomainByIntentId(b.getIntentId());
    }

    // ---- 内部工具 ----

    private void evictDomainByIntentId(Long intentId) {
        IntentDO intent = intentMapper.selectById(intentId);
        if (intent == null) return;
        DomainDO domain = domainMapper.selectById(intent.getDomainId());
        if (domain != null) domainRepository.evict(domain.getCode());
    }
}

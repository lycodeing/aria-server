package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.domain.IntentDO;
import com.aria.conversation.infrastructure.dit.domain.IntentSlotDO;
import com.aria.conversation.infrastructure.dit.domain.IntentToolDO;
import com.aria.conversation.infrastructure.dit.domain.ToolDO;
import com.aria.conversation.application.service.payload.SessionDomainSwitchVO;
import com.aria.conversation.infrastructure.dit.mapper.DomainMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentSlotMapper;
import com.aria.conversation.infrastructure.dit.mapper.IntentToolMapper;
import com.aria.conversation.infrastructure.dit.mapper.ToolMapper;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DIT 框架管理应用服务。
 * 领域、意图、槽位、工具的 CRUD 编排，同时维护缓存一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DitManageAppService {

    private static final int NOT_FOUND = 40400;

    private final DomainMapper domainMapper;
    private final IntentMapper intentMapper;
    private final IntentSlotMapper slotMapper;
    private final ToolMapper toolMapper;
    private final IntentToolMapper intentToolMapper;
    private final DomainRepository domainRepository; // 用于缓存失效
    private final SessionDomainSwitchRepository domainSwitchRepo;

    // ---- 领域 ----

    /**
     * 查询所有领域列表。
     *
     * @return 领域 DO 列表
     */
    public List<DomainDO> listDomains() {
        return domainMapper.selectList(null);
    }

    /**
     * 根据 ID 查询领域。
     *
     * @param id 领域 ID
     * @return 领域 DO
     * @throws BusinessException 领域不存在时抛出
     */
    public DomainDO getDomain(Long id) {
        DomainDO domain = domainMapper.selectById(id);
        if (domain == null) {
            throw new BusinessException(NOT_FOUND, "领域不存在: " + id);
        }
        return domain;
    }

    /**
     * 创建新领域。
     *
     * @param domain 领域 DO
     * @return 创建后的领域 DO
     */
    @Transactional(rollbackFor = Exception.class)
    public DomainDO createDomain(DomainDO domain) {
        domainMapper.insert(domain);
        log.info("创建领域: id={}, code={}", domain.getId(), domain.getCode());
        return domain;
    }

    /**
     * 更新领域，并失效相关缓存。
     *
     * @param domain 领域 DO
     * @throws BusinessException 领域不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateDomain(DomainDO domain) {
        if (domainMapper.updateById(domain) == 0) {
            throw new BusinessException(NOT_FOUND, "领域不存在: " + domain.getId());
        }
        log.info("更新领域: id={}", domain.getId());
        domainRepository.evict(domain.getCode());
    }

    /**
     * 删除领域，并失效相关缓存。
     *
     * @param id 领域 ID
     * @throws BusinessException 领域不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDomain(Long id) {
        DomainDO domainDO = getDomain(id);
        domainMapper.deleteById(id);
        log.info("删除领域: id={}, code={}", id, domainDO.getCode());
        domainRepository.evict(domainDO.getCode());
    }

    // ---- 意图 ----

    /**
     * 查询指定领域下的意图列表（按 sortOrder 升序）。
     *
     * @param domainId 领域 ID
     * @return 意图 DO 列表
     */
    public List<IntentDO> listIntents(Long domainId) {
        return intentMapper.selectList(
                new LambdaQueryWrapper<IntentDO>().eq(IntentDO::getDomainId, domainId)
                        .orderByAsc(IntentDO::getSortOrder));
    }

    /**
     * 创建新意图，并失效所属领域缓存。
     *
     * @param intent 意图 DO
     * @return 创建后的意图 DO
     */
    @Transactional(rollbackFor = Exception.class)
    public IntentDO createIntent(IntentDO intent) {
        intentMapper.insert(intent);
        log.info("创建意图: id={}, domainId={}", intent.getId(), intent.getDomainId());
        evictDomainByIntentId(intent.getId());
        return intent;
    }

    /**
     * 更新意图，并失效所属领域缓存。
     *
     * @param intent 意图 DO
     * @throws BusinessException 意图不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateIntent(IntentDO intent) {
        if (intentMapper.updateById(intent) == 0) {
            throw new BusinessException(NOT_FOUND, "意图不存在: " + intent.getId());
        }
        log.info("更新意图: id={}", intent.getId());
        evictDomainByIntentId(intent.getId());
    }

    /**
     * 删除意图，并级联删除其下所有槽位和工具绑定。
     * 缓存失效在删除记录前执行，确保失效时数据仍可读取。
     *
     * @param intentId 意图 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteIntent(Long intentId) {
        evictDomainByIntentId(intentId);  // 先失效缓存，此时记录仍在
        intentMapper.deleteById(intentId);
        // 级联删除槽位和绑定
        slotMapper.delete(new LambdaQueryWrapper<IntentSlotDO>().eq(IntentSlotDO::getIntentId, intentId));
        intentToolMapper.delete(new LambdaQueryWrapper<IntentToolDO>().eq(IntentToolDO::getIntentId, intentId));
        log.info("删除意图(级联): intentId={}", intentId);
    }

    // ---- 槽位 ----

    /**
     * 查询指定意图下的槽位列表。
     *
     * @param intentId 意图 ID
     * @return 槽位 DO 列表
     */
    public List<IntentSlotDO> listSlots(Long intentId) {
        return slotMapper.findByIntentId(intentId);
    }

    /**
     * 创建新槽位，并失效所属领域缓存。
     *
     * @param slot 槽位 DO
     * @return 创建后的槽位 DO
     */
    @Transactional(rollbackFor = Exception.class)
    public IntentSlotDO createSlot(IntentSlotDO slot) {
        slotMapper.insert(slot);
        log.info("创建槽位: id={}, intentId={}", slot.getId(), slot.getIntentId());
        evictDomainByIntentId(slot.getIntentId());
        return slot;
    }

    /**
     * 更新槽位，并失效所属领域缓存。
     *
     * @param slot 槽位 DO
     * @throws BusinessException 槽位不存在或更新失败时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSlot(IntentSlotDO slot) {
        // 先查出已有记录获取 intentId，updateById 不含 intentId 时缓存失效会拿到 null
        IntentSlotDO existing = slotMapper.selectById(slot.getId());
        if (existing == null) {
            throw new BusinessException(NOT_FOUND, "槽位不存在: " + slot.getId());
        }
        if (slotMapper.updateById(slot) == 0) {
            throw new BusinessException(NOT_FOUND, "槽位更新失败: " + slot.getId());
        }
        log.info("更新槽位: id={}, intentId={}", slot.getId(), existing.getIntentId());
        evictDomainByIntentId(existing.getIntentId());
    }

    /**
     * 删除槽位，并失效所属领域缓存。
     *
     * @param slotId 槽位 ID
     * @throws BusinessException 槽位不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSlot(Long slotId) {
        IntentSlotDO slotDO = slotMapper.selectById(slotId);
        if (slotDO == null) {
            throw new BusinessException(NOT_FOUND, "槽位不存在: " + slotId);
        }
        slotMapper.deleteById(slotId);
        log.info("删除槽位: id={}", slotId);
        evictDomainByIntentId(slotDO.getIntentId());
    }

    // ---- 工具 ----

    /**
     * 查询所有工具列表。
     *
     * @return 工具 DO 列表
     */
    public List<ToolDO> listTools() {
        return toolMapper.selectList(null);
    }

    /**
     * 根据 ID 查询工具。
     *
     * @param id 工具 ID
     * @return 工具 DO
     * @throws BusinessException 工具不存在时抛出
     */
    public ToolDO getToolById(Long id) {
        ToolDO tool = toolMapper.selectById(id);
        if (tool == null) {
            throw new BusinessException(NOT_FOUND, "工具不存在: " + id);
        }
        return tool;
    }

    /**
     * 创建新工具。
     *
     * @param tool 工具 DO
     * @return 创建后的工具 DO
     */
    @Transactional(rollbackFor = Exception.class)
    public ToolDO createTool(ToolDO tool) {
        toolMapper.insert(tool);
        log.info("创建工具: id={}", tool.getId());
        return tool;
    }

    /**
     * 更新工具。
     *
     * @param tool 工具 DO
     * @throws BusinessException 工具不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTool(ToolDO tool) {
        if (toolMapper.updateById(tool) == 0) {
            throw new BusinessException(NOT_FOUND, "工具不存在: " + tool.getId());
        }
        log.info("更新工具: id={}", tool.getId());
    }

    /**
     * 删除工具。
     *
     * @param toolId 工具 ID
     * @throws BusinessException 工具不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTool(Long toolId) {
        if (toolMapper.selectById(toolId) == null) {
            throw new BusinessException(NOT_FOUND, "工具不存在: " + toolId);
        }
        toolMapper.deleteById(toolId);
        log.info("删除工具: id={}", toolId);
    }

    // ---- 意图-工具绑定 ----

    /**
     * 查询指定意图绑定的工具列表。
     *
     * @param intentId 意图 ID
     * @return 意图-工具绑定 DO 列表
     */
    public List<IntentToolDO> listBindings(Long intentId) {
        return intentToolMapper.findByIntentId(intentId);
    }

    /**
     * 创建意图-工具绑定，并失效所属领域缓存。
     *
     * @param binding 绑定 DO
     * @return 创建后的绑定 DO
     */
    @Transactional(rollbackFor = Exception.class)
    public IntentToolDO createBinding(IntentToolDO binding) {
        intentToolMapper.insert(binding);
        log.info("创建绑定: id={}, intentId={}", binding.getId(), binding.getIntentId());
        evictDomainByIntentId(binding.getIntentId());
        return binding;
    }

    /**
     * 删除意图-工具绑定，并失效所属领域缓存。
     *
     * @param bindingId 绑定 ID
     * @throws BusinessException 绑定不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBinding(Long bindingId) {
        IntentToolDO bindingDO = intentToolMapper.selectById(bindingId);
        if (bindingDO == null) {
            throw new BusinessException(NOT_FOUND, "绑定不存在: " + bindingId);
        }
        intentToolMapper.deleteById(bindingId);
        log.info("删除绑定: id={}", bindingId);
        evictDomainByIntentId(bindingDO.getIntentId());
    }

    // ---- 会话域历史 ----

    /**
     * 查询会话的域切换历史（按时间升序）。
     *
     * @param sessionId 会话 ID
     * @return 切换历史 VO 列表
     */
    public List<SessionDomainSwitchVO> getSessionDomainHistory(String sessionId) {
        return domainSwitchRepo.findHistory(sessionId).stream()
                .map(SessionDomainSwitchVO::from)
                .toList();
    }

    // ---- 内部工具 ----

    /**
     * 根据意图 ID 查找所属领域并使缓存失效。
     * 若意图或领域不存在则静默跳过（用于删除场景）。
     *
     * @param intentId 意图 ID
     */
    private void evictDomainByIntentId(Long intentId) {
        IntentDO intent = intentMapper.selectById(intentId);
        if (intent == null) {
            return;
        }
        DomainDO domain = domainMapper.selectById(intent.getDomainId());
        if (domain != null) {
            domainRepository.evict(domain.getCode());
        }
    }
}

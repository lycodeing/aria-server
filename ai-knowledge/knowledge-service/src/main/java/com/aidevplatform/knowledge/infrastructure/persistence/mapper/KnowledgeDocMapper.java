package com.aidevplatform.knowledge.infrastructure.persistence.mapper;

import com.aidevplatform.knowledge.infrastructure.persistence.entity.KnowledgeDocEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档 Mapper。
 * 标准 CRUD 由 BaseMapper 提供。
 * 过期文档查询使用 LambdaQueryWrapper，无需自定义方法。
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDocEntity> {
}

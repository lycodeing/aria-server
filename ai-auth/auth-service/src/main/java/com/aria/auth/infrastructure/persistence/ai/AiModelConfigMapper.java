package com.aria.auth.infrastructure.persistence.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 模型配置 Mapper。
 */
@Mapper
public interface AiModelConfigMapper extends BaseMapper<AiModelConfigDO> {

    /**
     * 按模型类型将所有默认配置的 is_default 置为 false。
     * CHAT 和 EMBEDDING 互相独立，setDefault 时只清同类型的默认，不影响另一类型。
     *
     * @param modelType 模型类型：CHAT / EMBEDDING
     * @return 受影响行数
     */
    default int clearAllDefaultByType(String modelType) {
        return update(null, Wrappers.<AiModelConfigDO>lambdaUpdate()
                .set(AiModelConfigDO::getIsDefault, false)
                .eq(AiModelConfigDO::getModelType, modelType)
                .eq(AiModelConfigDO::getIsDefault, true)
                .isNull(AiModelConfigDO::getDeletedAt));
    }
}

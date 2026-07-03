package com.aria.auth.infrastructure.persistence.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
     */
    @Update("UPDATE cs_auth.ai_model_config SET is_default = FALSE " +
            "WHERE model_type = #{modelType} AND is_default = TRUE AND deleted_at IS NULL")
    int clearAllDefaultByType(@Param("modelType") String modelType);
}

package com.aria.customerservice.auth.infrastructure.persistence.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * AI 模型配置 Mapper。
 */
@Mapper
public interface AiModelConfigMapper extends BaseMapper<AiModelConfigDO> {

    /**
     * 将所有已启用配置的 is_default 置为 false（设置新默认前调用）。
     */
    @Update("UPDATE cs_auth.ai_model_config SET is_default = FALSE " +
            "WHERE is_default = TRUE AND deleted_at IS NULL")
    int clearAllDefault();
}

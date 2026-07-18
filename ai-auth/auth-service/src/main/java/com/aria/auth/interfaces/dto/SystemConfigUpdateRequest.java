package com.aria.auth.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 系统配置更新请求体
 * <p>configKey 与 configType 为只读，不允许通过更新接口修改，
 * 因此不出现在此请求体中；仅 configValue、description、isEnabled 可修改。</p>
 */
@Data
public class SystemConfigUpdateRequest {

    /**
     * 配置值（统一字符串存储）
     */
    @NotBlank(message = "配置值不能为空")
    private String configValue;

    /**
     * 配置描述，供管理员阅读
     */
    @Size(max = 255, message = "描述长度不超过 255 字符")
    private String description;

    /**
     * 是否启用
     */
    private Boolean isEnabled;
}

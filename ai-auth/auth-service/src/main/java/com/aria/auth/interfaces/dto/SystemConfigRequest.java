package com.aria.auth.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 系统配置创建 / 更新请求体
 * <p>update 接口中 configKey 和 configType 字段不生效（只读），
 * 仅 configValue、description、isEnabled 可修改。</p>
 */
@Data
public class SystemConfigRequest {

    /**
     * 配置键，仅 create 接口生效。
     * 格式：小写字母 + 数字 + 点，例如 agent.maxConcurrent
     */
    @NotBlank(message = "配置键不能为空")
    @Size(max = 100, message = "配置键长度不超过 100 字符")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9.]+$",
             message = "配置键只能包含字母、数字和点，且必须以字母开头")
    private String configKey;

    /** 配置值（统一字符串存储） */
    @NotBlank(message = "配置值不能为空")
    private String configValue;

    /** 配置类型：SYSTEM | CUSTOMER_SERVICE，仅 create 接口生效 */
    @NotBlank(message = "配置类型不能为空")
    @Pattern(regexp = "^(SYSTEM|CUSTOMER_SERVICE)$", message = "配置类型无效，仅支持 SYSTEM 或 CUSTOMER_SERVICE")
    private String configType;

    /** 配置描述，供管理员阅读 */
    @Size(max = 255, message = "描述长度不超过 255 字符")
    private String description;

    /** 是否启用，默认 true */
    private Boolean isEnabled;
}

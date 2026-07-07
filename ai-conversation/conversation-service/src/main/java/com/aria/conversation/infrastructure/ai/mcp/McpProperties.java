package com.aria.conversation.infrastructure.ai.mcp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP（Model Context Protocol）服务端连接配置。
 *
 * <p>支持多个 MCP 服务端，每个可通过 stdio 子进程或 HTTP SSE 连接。
 * {@code servers} 为空时 MCP 工具自动禁用，不影响域工具和内置工具正常运行。
 *
 * <p>配置示例：
 * <pre>{@code
 * mcp:
 *   servers:
 *     - key: my-tools
 *       type: STDIO
 *       command: ["/usr/bin/node", "/opt/my-mcp/index.js"]
 *       enabled: true
 *     - key: remote-tools
 *       type: HTTP
 *       url: http://localhost:3001/sse
 *       enabled: false
 * }</pre>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** MCP 服务端列表，空列表表示不启用 MCP 工具 */
    @Valid
    private List<ServerConfig> servers = new ArrayList<>();

    /** 单个 MCP 服务端配置项 */
    @Data
    public static class ServerConfig {

        /** 服务端唯一标识，用于日志和多客户端区分，不能为空 */
        @NotBlank(message = "MCP server key 不能为空")
        private String key;

        /** 传输类型：STDIO（子进程）或 HTTP（SSE） */
        private TransportType type = TransportType.STDIO;

        /**
         * STDIO 模式：启动子进程的命令及参数，type=STDIO 时不能为空。
         * 示例：["/usr/bin/node", "/opt/my-mcp/index.js"]
         */
        private List<String> command = new ArrayList<>();

        /**
         * HTTP 模式：MCP 服务端 SSE 地址，type=HTTP 时不能为空。
         * 示例：http://localhost:3001/sse
         */
        private String url;

        /** 是否启用此服务端，false 时跳过初始化 */
        private boolean enabled = true;

        /** 是否打印传输层事件日志（调试用） */
        private boolean logEvents = false;
    }

    /** MCP 传输层协议类型 */
    public enum TransportType {
        /** 通过 stdio 启动并通信的本地子进程 */
        STDIO,
        /** 通过 HTTP SSE 连接的远程服务端 */
        HTTP
    }
}

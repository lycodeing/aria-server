package com.aria.conversation.infrastructure.ai.mcp;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端注册表，管理所有 MCP 服务端连接的生命周期。
 *
 * <p>启动时根据 {@link McpProperties} 初始化各 MCP 客户端；
 * 提供聚合 {@link ToolProvider} 供 {@code DomainAgentService} 在 {@code toolProvider} 中合并使用。
 * 单个服务端初始化失败只记录错误日志，不阻断其他服务端或应用启动。
 *
 * <p>若 {@code mcp.servers} 为空，{@link #getToolProvider()} 返回空结果提供者，
 * 对现有域工具和内置工具零影响。
 *
 * <p>API 陷阱（langchain4j-mcp 1.1.0-beta7）：
 * {@code DefaultMcpClient} / {@code StdioMcpTransport} / {@code HttpMcpTransport}
 * 只有 {@code new X.Builder()} 内部类构造形式，无静态 {@code builder()} 工厂方法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClientRegistry implements DisposableBean {

    private final McpProperties properties;

    /** 已成功初始化的 MCP 客户端 */
    private final List<McpClient> activeClients = new ArrayList<>();

    /**
     * 无可用客户端时返回空结果，避免调用方做 null 判断。
     */
    private ToolProvider mcpToolProvider = request -> ToolProviderResult.builder().build();

    @PostConstruct
    public void init() {
        for (McpProperties.ServerConfig cfg : properties.getServers()) {
            if (!cfg.isEnabled()) {
                log.info("[MCP] 跳过已禁用服务端 key={}", cfg.getKey());
                continue;
            }
            try {
                McpTransport transport = buildTransport(cfg);
                McpClient client = new DefaultMcpClient.Builder()
                        .key(cfg.getKey())
                        .transport(transport)
                        .build();
                activeClients.add(client);
                log.info("[MCP] 已连接服务端 key={} type={}", cfg.getKey(), cfg.getType());
            } catch (Exception e) {
                log.error("[MCP] 连接服务端失败 key={}，该服务端工具将不可用", cfg.getKey(), e);
            }
        }

        if (!activeClients.isEmpty()) {
            mcpToolProvider = McpToolProvider.builder()
                    .mcpClients(activeClients)
                    .build();
            log.info("[MCP] ToolProvider 已就绪，活跃客户端数={}", activeClients.size());
        } else {
            log.info("[MCP] 无活跃服务端，MCP 工具已禁用");
        }
    }

    /**
     * 返回聚合所有活跃 MCP 客户端工具的 {@link ToolProvider}。
     * 每次 AI 调用前动态获取最新工具列表（McpToolProvider 内部有缓存）。
     */
    public ToolProvider getToolProvider() {
        return mcpToolProvider;
    }

    /** Spring 容器销毁时关闭所有连接，释放子进程或网络资源 */
    @Override
    public void destroy() {
        activeClients.forEach(client -> {
            try {
                client.close();
                log.info("[MCP] 已关闭客户端 key={}", client.key());
            } catch (Exception e) {
                log.warn("[MCP] 关闭客户端异常 key={}", client.key(), e);
            }
        });
        activeClients.clear();
    }

    private McpTransport buildTransport(McpProperties.ServerConfig cfg) {
        return switch (cfg.getType()) {
            case STDIO -> new StdioMcpTransport.Builder()
                    .command(cfg.getCommand())
                    .logEvents(cfg.isLogEvents())
                    .build();
            case HTTP -> new HttpMcpTransport.Builder()
                    .sseUrl(cfg.getUrl())
                    .logRequests(cfg.isLogEvents())
                    .logResponses(cfg.isLogEvents())
                    .build();
        };
    }
}

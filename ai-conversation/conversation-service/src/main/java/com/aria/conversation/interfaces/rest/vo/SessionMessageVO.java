package com.aria.conversation.interfaces.rest.vo;

/**
 * 会话消息记录 VO（返回给前端会话查询详情抽屉）。
 *
 * @param role          消息角色：user / assistant / agent / system / tool
 * @param content       消息内容
 * @param seq           session 内单调递增序号（可能为 null）
 * @param timestamp     消息毫秒时间戳（可能为 null）
 * @param toolName      工具名（仅 role=tool）
 * @param toolRequestId 工具请求 ID（仅 role=tool）
 */
public record SessionMessageVO(
        String role,
        String content,
        Long seq,
        Long timestamp,
        String toolName,
        String toolRequestId
) {}

package com.aria.conversation.application.dto;

/**
 * 会话初始化请求体，由 chat-widget 打开时传入。
 */
public class InitSessionRequest {

    /** 访客展示名称，可选，默认 "访客" */
    private String visitorName;

    public String getVisitorName() { return visitorName; }
    public void setVisitorName(String visitorName) { this.visitorName = visitorName; }
}

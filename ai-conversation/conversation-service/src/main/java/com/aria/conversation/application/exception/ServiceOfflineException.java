package com.aria.conversation.application.exception;

/**
 * 非服务时间尝试转人工时抛出，携带离线回复消息。
 * 由 ChatAppService 捕获后转换为错误码 40301。
 */
public class ServiceOfflineException extends RuntimeException {

    private final String offlineMessage;
    private final String nextOpenTime;

    public ServiceOfflineException(String offlineMessage, String nextOpenTime) {
        super(offlineMessage);
        this.offlineMessage = offlineMessage;
        this.nextOpenTime   = nextOpenTime;
    }

    public String getOfflineMessage() { return offlineMessage; }
    public String getNextOpenTime()   { return nextOpenTime;   }
}

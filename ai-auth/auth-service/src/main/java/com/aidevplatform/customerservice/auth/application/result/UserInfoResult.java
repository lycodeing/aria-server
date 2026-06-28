package com.aidevplatform.customerservice.auth.application.result;

import java.util.List;

/**
 * 用户信息查询结果（强类型，替代 Map&lt;String, Object&gt;）。
 *
 * @author aidevplatform
 */
public class UserInfoResult {

    private final String userId;
    private final String username;
    private final String realName;
    private final String avatar;
    private final List<String> roles;
    private final String homePath;
    private final String desc;

    public UserInfoResult(String userId, String username, String realName,
                          String avatar, List<String> roles,
                          String homePath, String desc) {
        this.userId = userId;
        this.username = username;
        this.realName = realName;
        this.avatar = avatar;
        this.roles = roles;
        this.homePath = homePath;
        this.desc = desc;
    }

    public String getUserId()   { return userId; }
    public String getUsername() { return username; }
    public String getRealName() { return realName; }
    public String getAvatar()   { return avatar; }
    public List<String> getRoles() { return roles; }
    public String getHomePath() { return homePath; }
    public String getDesc()     { return desc; }
}

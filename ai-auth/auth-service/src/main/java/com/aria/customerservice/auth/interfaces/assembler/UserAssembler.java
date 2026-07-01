package com.aria.auth.interfaces.assembler;

import com.aria.auth.domain.model.user.User;
import com.aria.auth.interfaces.rest.vo.UserVO;

/**
 * 用户领域对象 ↔ 接口层 VO 转换器。
 * 静态工具类，集中管理 User → UserVO 的映射逻辑，Controller 不内联转换代码。
 */
public final class UserAssembler {

    private UserAssembler() {}

    public static UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId().getValue());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus().name().toLowerCase());
        vo.setProvider(user.getProvider().name());
        if (user.getLastLoginAt() != null) {
            vo.setLastLoginAt(user.getLastLoginAt().toString());
        }
        return vo;
    }
}

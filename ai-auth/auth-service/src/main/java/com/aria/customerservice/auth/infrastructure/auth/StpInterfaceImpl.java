package com.aria.customerservice.auth.infrastructure.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.aria.customerservice.auth.domain.repository.IUserRepository;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class StpInterfaceImpl implements StpInterface {
    private final IUserRepository userRepo;
    public StpInterfaceImpl(IUserRepository userRepo) { this.userRepo = userRepo; }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long uid = parseLong(loginId);
        return uid == null ? List.of() : userRepo.findPermissionKeysByUserId(uid);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long uid = parseLong(loginId);
        return uid == null ? List.of() : userRepo.findRoleKeysByUserId(uid);
    }

    private Long parseLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return null; }
    }
}

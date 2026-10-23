package com.zer0drv.blog.admin.service;

import com.zer0drv.blog.auth.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local command only; deliberately has no HTTP controller. */
@Service
@RequiredArgsConstructor
public class OwnerRecoveryService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    @Transactional
    public void recover(String username, String password, boolean initialize) {
        if (password == null || password.length() < 6 || password.length() > 72)
            throw new IllegalArgumentException("恢复密码须为 6–72 个字符");
        jdbc.queryForList("SELECT id FROM owner_operation_lock WHERE id=1 FOR UPDATE");
        var rows = jdbc.queryForList("SELECT id,role FROM `user` WHERE username=? AND deleted=0 FOR UPDATE", username);
        if (rows.isEmpty()) throw new IllegalArgumentException("指定账号不存在");
        var account = rows.getFirst();
        if (initialize) {
            if (!"ADMIN".equals(account.get("role")) || jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE role='SUPER_ADMIN' AND deleted=0", Long.class) != 0)
                throw new IllegalArgumentException("初始化要求尚无超级管理员且目标为已有管理员");
        } else if (!"SUPER_ADMIN".equals(account.get("role"))) throw new IllegalArgumentException("只能恢复已有超级管理员");
        long user = ((Number) account.get("id")).longValue();
        jdbc.update("UPDATE `user` SET role='SUPER_ADMIN',status=0,password=? WHERE id=?", passwords.encode(password), user);
        jdbc.update("DELETE FROM totp_recovery WHERE user_id=?", user);
        jdbc.update("DELETE FROM totp_challenge WHERE user_id=?", user);
        jdbc.update("DELETE FROM user_totp WHERE user_id=?", user);
        tokens.blackUserTokens(user);
        jdbc.update("INSERT INTO owner_operation_audit(operator_id,target_id,action,outcome) VALUES(0,?,?,'SUCCESS')",
                user, initialize ? "LOCAL_INITIALIZE" : "LOCAL_RECOVER");
    }
}

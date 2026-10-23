package com.zer0drv.blog.admin.service;

import com.zer0drv.blog.auth.service.TotpService;
import com.zer0drv.blog.auth.service.TokenService;
import com.zer0drv.blog.auth.service.TotpCrypto;
import com.zer0drv.blog.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor=BusinessException.class)
public class OwnerOperations {
    private final JdbcTemplate jdbc;
    private final TotpService totp;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    public record Request(String action, String password, String code, String confirmation, String email) {}
    private static BusinessException denied() { return new BusinessException("40300", "操作不允许或确认信息不匹配"); }
    public void verifyOwner(long operator, String password, String code) {
        jdbc.queryForList("SELECT id FROM owner_operation_lock WHERE id=1 FOR UPDATE");
        var owners = jdbc.queryForList("SELECT id FROM `user` WHERE id=? AND role='SUPER_ADMIN' AND status=0 AND deleted=0", operator);
        if (owners.isEmpty()) throw denied();
        totp.reauthenticate(operator, password, code);
    }
    public void execute(long operator, long target, Request request) {
        String action = request.action();
        if (action == null || !Set.of("FREEZE", "UNFREEZE", "DEMOTE", "LOGOUT", "RESET_PASSWORD", "EMAIL", "RESET_TOTP", "TRANSFER").contains(action)) throw denied();
        try {
            verifyOwner(operator, request.password(), request.code());
            var rows = jdbc.queryForList("SELECT username,role,status,email FROM `user` WHERE id=? AND deleted=0 FOR UPDATE", target);
            if (rows.isEmpty() || target == operator || !"ADMIN".equals(rows.getFirst().get("role"))
                    || !rows.getFirst().get("username").equals(request.confirmation())) throw denied();
            switch (action) {
                case "FREEZE" -> jdbc.update("UPDATE `user` SET status=1 WHERE id=?", target);
                case "UNFREEZE" -> jdbc.update("UPDATE `user` SET status=0 WHERE id=?", target);
                case "DEMOTE" -> jdbc.update("UPDATE `user` SET role='USER' WHERE id=?", target);
                case "RESET_PASSWORD" -> {
                    if (String.valueOf(rows.getFirst().get("email")).endsWith("@oauth.local"))
                        throw new BusinessException("40023", "请先设置可接收找回密码邮件的邮箱");
                    // No temporary password is exposed. Account owner uses the existing email reset flow.
                    jdbc.update("UPDATE `user` SET password=? WHERE id=?", passwords.encode(TotpCrypto.randomToken()), target);
                }
                case "EMAIL" -> {
                    String email = request.email();
                    if (email == null || email.length() > 128 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw denied();
                    if (jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email=? AND id<>?", Long.class, email, target) > 0) throw denied();
                    jdbc.update("UPDATE `user` SET email=? WHERE id=?", email, target);
                }
                case "RESET_TOTP" -> {
                    jdbc.update("DELETE FROM totp_recovery WHERE user_id=?", target);
                    jdbc.update("DELETE FROM totp_challenge WHERE user_id=?", target);
                    jdbc.update("DELETE FROM user_totp WHERE user_id=?", target);
                }
                case "TRANSFER" -> {
                    if (((Number) rows.getFirst().get("status")).intValue() != 0) throw denied();
                    jdbc.update("UPDATE `user` SET role='SUPER_ADMIN' WHERE id=?", target);
                    jdbc.update("UPDATE `user` SET role='ADMIN' WHERE id=?", operator);
                    tokens.blackUserTokens(operator);
                }
                default -> { /* LOGOUT */ }
            }
            tokens.blackUserTokens(target);
            audit(operator, target, action, "SUCCESS");
        } catch (BusinessException error) {
            audit(operator, target, action, "DENIED");
            throw error;
        }
    }
    private void audit(long operator, long target, String action, String outcome) {
        jdbc.update("INSERT INTO owner_operation_audit(operator_id,target_id,action,outcome) VALUES(?,?,?,?)", operator, target, action, outcome);
    }
}

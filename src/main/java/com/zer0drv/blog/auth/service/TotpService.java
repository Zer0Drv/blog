package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** All operations lock the user row first: serialization also works across application instances. */
@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = BusinessException.class)
public class TotpService {
    private final JdbcTemplate jdbc;
    private final TotpCrypto crypto;
    private final PasswordEncoder passwords;
    private final TokenService tokens;

    private static BusinessException invalid() { return new BusinessException("40081", "验证失败或已过期，请重试；已使用的动态码需等待下一组"); }
    private static long now() { return Instant.now().getEpochSecond(); }
    private Map<String, Object> lock(long user) {
        var rows = jdbc.queryForList("SELECT id, username, password, role, status FROM `user` WHERE id=? AND deleted=0 FOR UPDATE", user);
        if (rows.isEmpty() || ((Number) rows.getFirst().get("status")).intValue() != 0) throw invalid();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_totp WHERE user_id=?", Integer.class, user) == 0)
            jdbc.update("INSERT INTO user_totp(user_id) VALUES (?)", user);
        return rows.getFirst();
    }
    private Map<String, Object> state(long user) { return jdbc.queryForMap("SELECT * FROM user_totp WHERE user_id=? FOR UPDATE", user); }
    private boolean enabled(Map<String, Object> row) { return Boolean.TRUE.equals(row.get("enabled")) || "1".equals(String.valueOf(row.get("enabled"))); }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private void allowed(Map<String, Object> state) {
        if (number(state, "blocked_until") > now()) throw new BusinessException("40082", "验证失败次数过多，请五分钟后重试");
    }
    private void fail(long user, Map<String, Object> state) {
        long failures = number(state, "failures") + 1;
        jdbc.update("UPDATE user_totp SET failures=?, blocked_until=? WHERE user_id=?",
                failures >= 5 ? 0 : failures, failures >= 5 ? now() + 300 : 0, user);
        // BusinessException must commit the failure counter, including on invalid setup/password requests.
        throw invalid();
    }
    private void password(long user, Map<String, Object> account, Map<String, Object> state, String password) {
        allowed(state);
        if (password == null || !passwords.matches(password, (String) account.get("password"))) fail(user, state);
    }
    private String fingerprint(Map<String, Object> account, Map<String, Object> state) {
        return TotpCrypto.hash(account.get("password") + ":" + account.get("role") + ":" + account.get("status") + ":" + state.get("secret"));
    }

    public Map<String, Object> status(long user) {
        lock(user); var state = state(user);
        return Map.of("enabled", enabled(state), "available", crypto.configured(), "recoveryRemaining",
                jdbc.queryForObject("SELECT COUNT(*) FROM totp_recovery WHERE user_id=?", Integer.class, user));
    }

    public Map<String, String> setup(long user, String password) {
        var account = lock(user); var state = state(user);
        password(user, account, state, password);
        if (enabled(state)) throw new BusinessException("40083", "请先关闭现有两步验证");
        if (!crypto.configured()) throw new BusinessException("40084", "站点尚未配置两步验证密钥，请联系管理员");
        String secret = TotpCrypto.newSecret();
        jdbc.update("UPDATE user_totp SET secret=?, setup_expires=?, last_step=-1 WHERE user_id=?", crypto.encrypt(user, secret), now() + 600, user);
        String label = URLEncoder.encode("Blog:" + account.get("username"), StandardCharsets.UTF_8).replace("+", "%20");
        return Map.of("secret", secret, "uri", "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Blog&algorithm=SHA1&digits=6&period=30");
    }

    public List<String> confirm(long user, String password, String code) {
        var account = lock(user); var state = state(user);
        password(user, account, state, password);
        if (enabled(state) || state.get("secret") == null || number(state, "setup_expires") <= now()) throw invalid();
        verify(user, state, code, false);
        jdbc.update("UPDATE user_totp SET enabled=TRUE, setup_expires=0 WHERE user_id=?", user);
        var codes = recoveryCodes(user);
        invalidate(user);
        return codes;
    }

    public void disable(long user, String password, String code) {
        var account = lock(user); var state = state(user);
        password(user, account, state, password);
        if (!enabled(state)) throw invalid();
        verify(user, state, code, true);
        jdbc.update("UPDATE user_totp SET enabled=FALSE, secret=NULL, setup_expires=0, last_step=-1 WHERE user_id=?", user);
        jdbc.update("DELETE FROM totp_recovery WHERE user_id=?", user);
        invalidate(user);
    }

    public List<String> regenerate(long user, String password, String code) {
        var account = lock(user); var state = state(user);
        password(user, account, state, password);
        if (!enabled(state)) throw invalid();
        verify(user, state, code, true);
        var codes = recoveryCodes(user);
        invalidate(user);
        return codes;
    }

    private void invalidate(long user) {
        jdbc.update("DELETE FROM totp_challenge WHERE user_id=?", user);
        tokens.blackUserTokens(user);
    }
    private List<String> recoveryCodes(long user) {
        jdbc.update("DELETE FROM totp_recovery WHERE user_id=?", user);
        var codes = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            String code = TotpCrypto.randomToken().substring(0, 32); // 128 random bits per recovery code
            codes.add(code);
            jdbc.update("INSERT INTO totp_recovery(user_id,code_hash) VALUES (?,?)", user, TotpCrypto.hash(code));
        }
        return codes;
    }
    private void verify(long user, Map<String, Object> state, String code, boolean recovery) {
        allowed(state);
        long step = TotpCrypto.match(crypto.decrypt(user, (String) state.get("secret")), code, now(), number(state, "last_step"));
        boolean recovered = step < 0 && recovery && code != null && code.matches("[0-9a-f]{32}") &&
                jdbc.update("DELETE FROM totp_recovery WHERE user_id=? AND code_hash=?", user, TotpCrypto.hash(code)) == 1;
        if (step < 0 && !recovered) fail(user, state);
        jdbc.update("UPDATE user_totp SET failures=0, blocked_until=0, last_step=? WHERE user_id=?",
                step >= 0 ? step : number(state, "last_step"), user);
    }

    /** Called only AFTER primary password/OAuth authentication; the challenge itself grants no permissions. */
    public Map<String, String> primary(long user, String verifiedPasswordHash) {
        var account = lock(user); var state = state(user);
        if (verifiedPasswordHash != null && !verifiedPasswordHash.equals(account.get("password"))) throw invalid();
        if (!enabled(state)) return issueToken(user, account);
        allowed(state);
        String challenge = TotpCrypto.randomToken();
        jdbc.update("DELETE FROM totp_challenge WHERE user_id=?", user);
        jdbc.update("INSERT INTO totp_challenge(user_id,token_hash,credential_hash,expires_at) VALUES (?,?,?,?)",
                user, TotpCrypto.hash(challenge), fingerprint(account, state), now() + 300);
        return Map.of("mfa_required", "true", "challenge", challenge);
    }

    public Map<String, String> complete(String challenge, String code) {
        if (challenge == null || !challenge.matches("[0-9a-f]{64}")) throw invalid();
        String hash = TotpCrypto.hash(challenge);
        var users = jdbc.queryForList("SELECT user_id FROM totp_challenge WHERE token_hash=?", Long.class, hash);
        if (users.isEmpty()) throw invalid();
        long user = users.getFirst();
        var account = lock(user); var state = state(user);
        // Re-read after the lock so concurrent completion/replacement cannot reuse the challenge.
        var challenges = jdbc.queryForList("SELECT * FROM totp_challenge WHERE user_id=? AND token_hash=? FOR UPDATE", user, hash);
        if (challenges.isEmpty()) throw invalid();
        var row = challenges.getFirst();
        if (!enabled(state) || number(row, "expires_at") <= now() || !fingerprint(account, state).equals(row.get("credential_hash"))) throw invalid();
        verify(user, state, code, true);
        jdbc.update("DELETE FROM totp_challenge WHERE user_id=?", user);
        return issueToken(user, account);
    }
    private Map<String, String> issueToken(long user, Map<String, Object> account) {
        return Map.of("access_token", tokens.generateToken(user, List.of("ROLE_" + account.get("role"))), "token_type", "Bearer");
    }
}

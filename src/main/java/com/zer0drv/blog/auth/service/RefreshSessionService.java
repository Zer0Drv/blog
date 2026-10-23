package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.auth.event.UserTokensRevoked;
import com.zer0drv.blog.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = BusinessException.class)
public class RefreshSessionService {
    public static final long TTL = 30L * 24 * 3600;
    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    private static BusinessException invalid() { return new BusinessException("40100", "会话已失效，请重新登录"); }
    private Map<String, Object> account(long user) {
        var rows = jdbc.queryForList("SELECT role,status,session_version FROM `user` WHERE id=? AND deleted=0 FOR UPDATE", user);
        if (rows.isEmpty() || ((Number) rows.getFirst().get("status")).intValue() != 0) throw invalid();
        return rows.getFirst();
    }
    private long version(Map<String,Object> account) { return ((Number) account.get("session_version")).longValue(); }
    private String issue(long user, long version, long expires, String family, String accessJti) {
        String raw = TotpCrypto.randomToken();
        jdbc.update("INSERT INTO auth_refresh_session(token_hash,user_id,session_version,expires_at,family,access_jti) VALUES(?,?,?,?,?,?)",
                TotpCrypto.hash(raw), user, version, expires, family, accessJti);
        return raw;
    }
    public String create(Jwt jwt) {
        long user = Long.parseLong(jwt.getSubject());
        var account = account(user);
        Number issuedVersion = jwt.getClaim("session_version");
        if ((issuedVersion == null ? 0 : issuedVersion.longValue()) != version(account)) throw invalid();
        return issue(user, version(account), Instant.now().getEpochSecond() + TTL, jwt.getId(), jwt.getId());
    }
    public record Rotation(String access, String refresh, long expires) {}
    public Rotation rotate(String raw) {
        if (raw == null || !raw.matches("[0-9a-f]{64}")) throw invalid();
        String hash = TotpCrypto.hash(raw);
        var rows = jdbc.queryForList("SELECT * FROM auth_refresh_session WHERE token_hash=?", hash);
        if (rows.isEmpty()) throw invalid();
        long user = ((Number) rows.getFirst().get("user_id")).longValue();
        var account = account(user); // Every rotation/revocation takes the same per-user lock.
        var row = jdbc.queryForMap("SELECT * FROM auth_refresh_session WHERE token_hash=? FOR UPDATE", hash);
        if (Boolean.TRUE.equals(row.get("consumed"))) {
            tokens.blackUserTokens(user); // Commits even though the reuse response is an error.
            throw invalid();
        }
        long expires = ((Number) row.get("expires_at")).longValue();
        if (Boolean.TRUE.equals(row.get("revoked")) || expires <= Instant.now().getEpochSecond()
                || ((Number) row.get("session_version")).longValue() != version(account)) throw invalid();
        jdbc.update("UPDATE auth_refresh_session SET consumed=TRUE WHERE token_hash=?", hash);
        String access = tokens.generateToken(user, List.of("ROLE_" + account.get("role")));
        return new Rotation(access, issue(user, version(account), expires, (String) row.get("family"), decoder.decode(access).getId()), expires);
    }
    @EventListener
    public void revoke(UserTokensRevoked event) {
        jdbc.update("UPDATE `user` SET session_version=session_version+1 WHERE id=?", event.userId());
        jdbc.update("UPDATE auth_refresh_session SET revoked=TRUE WHERE user_id=?", event.userId());
    }
    @EventListener
    public void revokeAccess(com.zer0drv.blog.auth.event.TokenRevoked event) {
        var sessions = jdbc.queryForList("SELECT user_id,family FROM auth_refresh_session WHERE access_jti=?", event.jti());
        for (var session : sessions) {
            // Serialize logout with rotation before reading the family's newly issued access tokens.
            jdbc.queryForList("SELECT id FROM `user` WHERE id=? FOR UPDATE", session.get("user_id"));
            var active = jdbc.queryForList("SELECT access_jti FROM auth_refresh_session WHERE family=? AND revoked=FALSE FOR UPDATE",
                    String.class, session.get("family"));
            jdbc.update("UPDATE auth_refresh_session SET revoked=TRUE WHERE family=?", session.get("family"));
            // Mark first: nested TokenRevoked listeners then see no active family members.
            active.stream().distinct().filter(jti -> !jti.equals(event.jti()))
                    .forEach(jti -> tokens.blackToken(jti, Instant.now().plusSeconds(900)));
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void cleanupExpired() {
        jdbc.update("DELETE FROM auth_refresh_session WHERE expires_at<?", Instant.now().getEpochSecond() - 86400);
    }
}

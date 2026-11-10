package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.auth.event.UserTokensRevoked;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = BusinessException.class)
public class RefreshSessionService {
    public static final long TTL = 30L * 24 * 3600;
    // Cover a full 15-minute access token plus the JWT decoder's 60-second clock skew.
    private static final long ACCESS_REVOCATION_SECONDS = 16 * 60;
    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    private final TotpService totp;

    private static BusinessException invalid() { return new BusinessException("40100", "会话已失效，请重新登录"); }
    private Map<String, Object> account(long user) {
        var rows = jdbc.queryForList("SELECT role,status,session_version FROM `user` WHERE id=? AND deleted=0 FOR UPDATE", user);
        if (rows.isEmpty() || ((Number) rows.getFirst().get("status")).intValue() != 0) throw invalid();
        return rows.getFirst();
    }
    private long version(Map<String,Object> account) { return ((Number) account.get("session_version")).longValue(); }
    private String issue(long user, long version, long expires, String family, String accessJti, String deviceLabel) {
        String raw = TotpCrypto.randomToken();
        jdbc.update("""
                INSERT INTO auth_refresh_session(token_hash,user_id,session_version,expires_at,family,access_jti,device_label,last_active_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, TotpCrypto.hash(raw), user, version, expires, family, accessJti, deviceLabel, Instant.now().getEpochSecond());
        return raw;
    }
    public String create(Jwt jwt) {
        return create(jwt, null);
    }
    public String create(Jwt jwt, String userAgent) {
        long user = JwtSubjects.userIdOf(jwt);
        var account = account(user);
        Number issuedVersion = jwt.getClaim("session_version");
        if ((issuedVersion == null ? 0 : issuedVersion.longValue()) != version(account)) throw invalid();
        return issue(user, version(account), Instant.now().getEpochSecond() + TTL, jwt.getId(), jwt.getId(), deviceLabel(userAgent));
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
        long expires = ((Number) row.get("expires_at")).longValue();
        if (Boolean.TRUE.equals(row.get("revoked")) || expires <= Instant.now().getEpochSecond()
                || ((Number) row.get("session_version")).longValue() != version(account)) throw invalid();
        if (Boolean.TRUE.equals(row.get("consumed"))) {
            tokens.blackUserTokens(user); // Reuse of a live family still revokes the account's sessions.
            throw invalid();
        }
        jdbc.update("UPDATE auth_refresh_session SET consumed=TRUE WHERE token_hash=?", hash);
        String access = tokens.generateToken(user, List.of("ROLE_" + account.get("role")));
        return new Rotation(access, issue(user, version(account), expires, (String) row.get("family"),
                decoder.decode(access).getId(), (String) row.get("device_label")), expires);
    }

    public record Device(String id, String label, long signedInAt, Long lastActiveAt, boolean current) {}

    @Transactional(readOnly = true)
    public List<Device> devices(Jwt jwt) {
        long user = JwtSubjects.userIdOf(jwt);
        long now = Instant.now().getEpochSecond();
        long version = versionOf(jwt);
        String current = currentFamily(user, jwt.getId(), version, now);
        return jdbc.query("""
                SELECT family, MAX(expires_at) AS expires_at, MAX(device_label) AS device_label,
                       MAX(last_active_at) AS last_active_at
                FROM auth_refresh_session
                WHERE user_id=? AND session_version=? AND expires_at>? AND consumed=FALSE AND revoked=FALSE
                GROUP BY family
                """, (row, index) -> {
            String family = row.getString("family");
            String label = row.getString("device_label");
            return new Device(deviceId(family), label == null ? "未知设备" : label,
                    row.getLong("expires_at") - TTL, row.getObject("last_active_at", Long.class), family.equals(current));
        }, user, version, now).stream().sorted(Comparator.comparing(Device::current).reversed()
                .thenComparing(Device::lastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingLong(Device::signedInAt).reversed())).toList();
    }

    public int revokeDevices(Jwt jwt, String id, String password, String code) {
        long user = JwtSubjects.userIdOf(jwt);
        long version = version(account(user));
        long now = Instant.now().getEpochSecond();
        if (version != versionOf(jwt)) throw invalid();
        String current = currentFamily(user, jwt.getId(), version, now);
        if (current == null) throw new BusinessException(StatusCode.LOGIN_STATUS_INVALID);
        if (deviceId(current).equals(id)) throw new BusinessException(StatusCode.CANNOT_OPERATE_SELF);

        // The user-row lock serializes this query and the changes below with refresh and logout.
        var families = jdbc.queryForList("""
                SELECT DISTINCT family FROM auth_refresh_session
                WHERE user_id=? AND session_version=? AND family<>?
                """, String.class, user, version, current);
        if (id != null) {
            families = families.stream().filter(family -> deviceId(family).equals(id)).toList();
            if (families.isEmpty()) throw new BusinessException(StatusCode.NO_RESOURCE_FOUND);
        }
        var active = Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT family FROM auth_refresh_session
                WHERE user_id=? AND session_version=? AND family<>? AND revoked=FALSE AND expires_at>?
                """, String.class, user, version, current, now - ACCESS_REVOCATION_SECONDS));
        var targets = families.stream().filter(active::contains).toList();
        if (targets.isEmpty()) return 0;
        totp.reauthenticate(user, password, code);
        targets.forEach(family -> revokeFamily(user, family, null));
        return targets.size();
    }

    private String currentFamily(long user, String jti, long version, long now) {
        return jdbc.queryForList("""
                SELECT family FROM auth_refresh_session
                WHERE user_id=? AND access_jti=? AND session_version=? AND expires_at>? AND revoked=FALSE
                """, String.class, user, jti, version, now).stream().findFirst().orElse(null);
    }

    private static long versionOf(Jwt jwt) {
        Number version = jwt.getClaim("session_version");
        return version == null ? 0 : version.longValue();
    }

    private static String deviceId(String family) {
        // A domain-separated, one-way identifier. Neither the family nor its JTI is sent to the UI.
        return TotpCrypto.hash("blog-device-management:" + family);
    }

    private static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return null;
        String ua = userAgent.substring(0, Math.min(userAgent.length(), 512)).toLowerCase(Locale.ROOT);
        String browser = ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/") ? "Edge"
                : ua.contains("opr/") ? "Opera"
                : ua.contains("firefox/") || ua.contains("fxios/") ? "Firefox"
                : ua.contains("chrome/") || ua.contains("crios/") ? "Chrome"
                : ua.contains("safari/") ? "Safari" : "未知浏览器";
        String platform = ua.contains("android") ? "Android" : ua.contains("iphone") || ua.contains("ipad") ? "iOS"
                : ua.contains("windows") ? "Windows" : ua.contains("macintosh") ? "macOS"
                : ua.contains("linux") ? "Linux" : "未知系统";
        return "未知浏览器".equals(browser) && "未知系统".equals(platform) ? null : browser + " · " + platform;
    }
    @EventListener
    public void revoke(UserTokensRevoked event) {
        jdbc.update("UPDATE `user` SET session_version=session_version+1 WHERE id=?", event.userId());
        jdbc.update("UPDATE auth_refresh_session SET revoked=TRUE WHERE user_id=?", event.userId());
    }
    @EventListener
    public void revokeAccess(com.zer0drv.blog.auth.event.TokenRevoked event) {
        var sessions = jdbc.queryForList("SELECT DISTINCT user_id,family FROM auth_refresh_session WHERE access_jti=? AND revoked=FALSE", event.jti());
        for (var session : sessions) {
            // Serialize logout with rotation before reading the family's newly issued access tokens.
            long user = ((Number) session.get("user_id")).longValue();
            jdbc.queryForList("SELECT id FROM `user` WHERE id=? FOR UPDATE", user);
            revokeFamily(user, (String) session.get("family"), event.jti());
        }
    }

    private void revokeFamily(long user, String family, String alreadyRevoked) {
        var issued = jdbc.queryForList("""
                SELECT access_jti FROM auth_refresh_session WHERE user_id=? AND family=? AND revoked=FALSE FOR UPDATE
                """, String.class, user, family);
        // Mark first so nested TokenRevoked listeners do not reprocess this family.
        jdbc.update("UPDATE auth_refresh_session SET revoked=TRUE WHERE user_id=? AND family=? AND revoked=FALSE", user, family);
        Instant revokeUntil = Instant.now().plusSeconds(ACCESS_REVOCATION_SECONDS);
        issued.stream().distinct().filter(jti -> !jti.equals(alreadyRevoked))
                .forEach(jti -> tokens.blackToken(jti, revokeUntil));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void cleanupExpired() {
        jdbc.update("DELETE FROM auth_refresh_session WHERE expires_at<?", Instant.now().getEpochSecond() - 86400);
    }
}

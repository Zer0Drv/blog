package com.zer0drv.blog.auth.service;

import com.zer0drv.blog.auth.event.UserTokensRevoked;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Password/role/ban changes invalidate pending primary-factor challenges as well as JWTs. */
@Component
@RequiredArgsConstructor
public class TotpRevocationListener {
    private final JdbcTemplate jdbc;
    @EventListener public void revoked(UserTokensRevoked event) {
        jdbc.update("DELETE FROM totp_challenge WHERE user_id=?", event.userId());
    }
}

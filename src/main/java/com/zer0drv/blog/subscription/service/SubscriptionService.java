package com.zer0drv.blog.subscription.service;
import com.zer0drv.blog.subscription.repository.SubscriptionRepository;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {
    public static final String EMAIL_SCENE = "article-subscription";
    private final UserService users;
    private final EmailCodeService emailCodes;
    private final SubscriptionRepository repository;
    public Map<String, Object> stats() { return repository.stats(); }
    public Map<String, Boolean> state(long userId) {
        User user = requireUser(userId);
        return Map.of("enabled", enabled(user), "emailEnabled", Short.valueOf((short) 1).equals(user.getArticleEmailNotifyEnabled()));
    }
    public void set(long userId, boolean enabled) {
        User user = requireUser(userId);
        var update = Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                .set(User::getArticleNotifyEnabled, enabled ? 1 : 0);
        if (enabled && !enabled(user)) {
            update.set(User::getArticleSubscribedAt, LocalDateTime.now().withNano(0))
                    .set(User::getArticleUnsubscribeToken, UUID.randomUUID().toString().replace("-", ""));
        }
        if (!enabled) update.set(User::getArticleEmailNotifyEnabled, 0).set(User::getArticleUnsubscribeToken, "");
        users.update(update);
    }
    public void sendEmailCode(long userId) {
        User user = requireUser(userId);
        if (!enabled(user) || user.getEmail() == null || user.getEmail().endsWith("@oauth.local"))
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "请先订阅并设置可用邮箱");
        emailCodes.sendCode(EMAIL_SCENE, user.getEmail());
    }
    public void confirmEmail(long userId, String code) {
        User user = requireUser(userId);
        if (!enabled(user)) throw new BusinessException(StatusCode.PARAM_INVALID);
        if (!emailCodes.verify(EMAIL_SCENE, user.getEmail(), code)) throw new BusinessException(StatusCode.EMAIL_CODE_INVALID);
        users.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                .eq(User::getArticleNotifyEnabled, 1).set(User::getArticleEmailNotifyEnabled, 1));
    }
    public void stopEmail(long userId) {
        users.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId).set(User::getArticleEmailNotifyEnabled, 0));
    }
    public void unsubscribe(String token) {
        if (token == null || !token.matches("[a-f0-9]{32}")) throw new BusinessException(StatusCode.PARAM_INVALID);
        // 幂等且无注册状态泄露；令牌只具有退订能力。
        users.update(Wrappers.lambdaUpdate(User.class).eq(User::getArticleUnsubscribeToken, token)
                .set(User::getArticleNotifyEnabled, 0).set(User::getArticleEmailNotifyEnabled, 0)
                .set(User::getArticleUnsubscribeToken, ""));
    }
    public static boolean enabled(User user) { return Short.valueOf((short) 1).equals(user.getArticleNotifyEnabled()); }
    private User requireUser(long id) {
        User user = users.getById(id);
        if (user == null) throw new BusinessException(StatusCode.USER_NOT_EXIST);
        return user;
    }
}

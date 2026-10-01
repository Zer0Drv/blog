package com.zer0drv.blog.config;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 初始管理员引导（#2）：历史 V2 迁移曾种子 admin/admin123，其 BCrypt 哈希随仓库公开，
 * 必须视为已泄露。本初始化器在启动期兜底（Flyway 迁移完成后执行）：
 * <ul>
 *   <li>admin 账号仍为公开种子口令 → 原地轮换（保留用户 id，不影响其文章归属）；</li>
 *   <li>库内已无任何 ADMIN → 重建初始管理员。</li>
 * </ul>
 * 新密码优先取环境变量 ADMIN_INITIAL_PASSWORD；未注入时随机生成并仅打印一次到启动日志，
 * 首登后请立即通过「修改密码」更换。
 *
 * @author Yoruhaki
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "blog.admin", name = "bootstrap-enabled", havingValue = "true", matchIfMissing = true)
public class AdminAccountInitializer implements ApplicationRunner {

    /**
     * 已泄露的 V2 种子口令哈希（admin/admin123），仅用于识别「尚未改密」的种子账号
     */
    private static final String LEGACY_SEED_PASSWORD_HASH =
            "$2a$10$ubGNSIh9Rg5Nl49HPoOD6ecRzSTnf6TgNBBPaN2uEpPS2eoPB2hXS";

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String configuredPassword;

    public AdminAccountInitializer(UserService userService,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${blog.admin.username:admin}") String adminUsername,
                                   @Value("${blog.admin.initial-password:}") String configuredPassword) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        User seeded = userService.getOne(Wrappers.lambdaQuery(User.class)
                .eq(User::getUsername, adminUsername));
        if (Objects.nonNull(seeded) && LEGACY_SEED_PASSWORD_HASH.equals(seeded.getPassword())) {
            // 种子口令从未修改：原地轮换，不重建（保留 id 与文章归属）
            rotatePassword(seeded);
            return;
        }
        long adminCount = userService.count(Wrappers.lambdaQuery(User.class)
                .eq(User::getRole, UserRole.ADMIN.name()));
        if (adminCount > 0) {
            return;
        }
        if (Objects.nonNull(seeded)) {
            log.error("库内无 ADMIN 且用户名 '{}' 已被非管理员账号占用，请人工处理初始管理员账号", adminUsername);
            return;
        }
        createAdmin();
    }

    /**
     * 原地轮换种子管理员口令
     */
    private void rotatePassword(User admin) {
        boolean fromEnv = Objects.nonNull(configuredPassword) && !configuredPassword.isBlank();
        String rawPassword = fromEnv ? configuredPassword : RandomUtil.randomString(16);
        admin.setPassword(passwordEncoder.encode(rawPassword));
        userService.updateById(admin);
        if (fromEnv) {
            log.warn("检测到已泄露的种子管理员口令，已按 ADMIN_INITIAL_PASSWORD 轮换（账号 '{}'），请首登后立即修改",
                    adminUsername);
        } else {
            log.warn("检测到已泄露的种子管理员口令，已轮换为随机密码（账号 '{}'）：{}（仅本次打印，请立即登录并修改）",
                    adminUsername, rawPassword);
        }
    }

    /**
     * 库内无 ADMIN 时重建初始管理员
     */
    private void createAdmin() {
        boolean fromEnv = Objects.nonNull(configuredPassword) && !configuredPassword.isBlank();
        String rawPassword = fromEnv ? configuredPassword : RandomUtil.randomString(16);
        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPassword(passwordEncoder.encode(rawPassword));
        admin.setEmail("admin@blog.local");
        admin.setNickname("博主");
        admin.setRole(UserRole.ADMIN.name());
        admin.setStatus((short) 0);
        boolean saved = userService.save(admin);
        if (!saved) {
            log.error("初始管理员创建失败，请检查数据库后重启");
            return;
        }
        if (fromEnv) {
            log.warn("初始管理员 '{}' 已创建（密码来自 ADMIN_INITIAL_PASSWORD），请首登后立即修改", adminUsername);
        } else {
            log.warn("初始管理员 '{}' 已创建，随机初始密码：{}（仅本次打印，请立即登录并修改）", adminUsername, rawPassword);
        }
    }
}

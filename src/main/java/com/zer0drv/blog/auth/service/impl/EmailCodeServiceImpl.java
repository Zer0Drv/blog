package com.zer0drv.blog.auth.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.zer0drv.blog.auth.service.EmailCodeService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 邮箱验证码服务实现（#3）：
 * - verify 失败计数：同一 scene+email 连续失败 5 次即作废当前验证码（需重新发送），防 6 位码爆破；
 * - dev 兜底日志不再打印明文验证码，仅提示 Redis 键位置。
 *
 * @author Yoruhaki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeServiceImpl implements EmailCodeService {

    private static final String CODE_KEY = "blog:email-code:%s:%s";
    private static final String LIMIT_KEY = "blog:email-code:limit:%s:%s";

    /**
     * 校验失败计数键（#3）：与验证码同 TTL
     */
    private static final String FAIL_KEY = "blog:email-code:fail:%s:%s";

    /**
     * 连续失败上限：达到即作废当前验证码（#3）
     */
    private static final long MAX_VERIFY_FAILURES = 5;

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration LIMIT_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Override
    public void sendCode(String scene, String email) {
        String limitKey = LIMIT_KEY.formatted(scene, email);
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(limitKey, "1", LIMIT_TTL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(StatusCode.EMAIL_CODE_TOO_FREQUENT);
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(CODE_KEY.formatted(scene, email), code, CODE_TTL);
        // 重发即重置失败计数（旧码同时被覆盖作废）
        stringRedisTemplate.delete(FAIL_KEY.formatted(scene, email));

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            // dev 兜底（#3）：不再明文打印验证码，仅提示到 Redis 读取；生产必须配置 spring.mail.*
            log.warn("【dev-email-fallback】scene={} email={}（未配置 SMTP，验证码仅写入 Redis，"
                    + "可用 redis-cli GET blog:email-code:{}:{} 读取；切勿在生产环境依赖此兜底）", scene, email, scene, email);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("【Blog】邮箱验证码");
        message.setText("您的验证码是：" + code + "，10 分钟内有效。若非本人操作请忽略。");
        mailSender.send(message);
    }

    @Override
    public boolean verify(String scene, String email, String code) {
        String key = CODE_KEY.formatted(scene, email);
        String stored = stringRedisTemplate.opsForValue().get(key);
        if (stored != null && stored.equals(code)) {
            // 成功即消费，并清失败计数
            stringRedisTemplate.delete(key);
            stringRedisTemplate.delete(FAIL_KEY.formatted(scene, email));
            return true;
        }
        // 失败计数（#3）：同一 scene+email 连续失败达到上限即作废当前验证码，必须重新发送
        String failKey = FAIL_KEY.formatted(scene, email);
        Long failures = stringRedisTemplate.opsForValue().increment(failKey);
        if (failures != null && failures == 1L) {
            stringRedisTemplate.expire(failKey, CODE_TTL);
        }
        if (failures != null && failures >= MAX_VERIFY_FAILURES) {
            stringRedisTemplate.delete(key);
            stringRedisTemplate.delete(failKey);
            log.warn("邮箱验证码连续失败 {} 次已作废（防爆破）：scene={} email={}", MAX_VERIFY_FAILURES, scene, email);
        }
        return false;
    }
}

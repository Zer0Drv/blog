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
 * @author Yoruhaki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeServiceImpl implements EmailCodeService {

    private static final String CODE_KEY = "blog:email-code:%s:%s";
    private static final String LIMIT_KEY = "blog:email-code:limit:%s:%s";
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

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            // dev 兜底：未配置 SMTP 时验证码进日志，联调不受影响；生产必须配置 spring.mail.*
            log.warn("【dev-email-fallback】scene={} email={} code={}（未配置 SMTP，验证码仅打印日志）", scene, email, code);
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
        if (stored == null || !stored.equals(code)) {
            return false;
        }
        stringRedisTemplate.delete(key);
        return true;
    }
}

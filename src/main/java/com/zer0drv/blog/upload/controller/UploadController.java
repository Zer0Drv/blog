package com.zer0drv.blog.upload.controller;

import com.zer0drv.blog.attachment.domain.Attachment;
import com.zer0drv.blog.attachment.service.AttachmentService;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.upload.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    /**
     * 每用户每日上传计数键（#6-4）：blog:upload:daily:{userId}:{yyyy-MM-dd}
     */
    private static final String DAILY_COUNT_KEY = "blog:upload:daily:%d:%s";

    private final UploadService uploadService;
    private final AttachmentService attachmentService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 每用户每日上传次数上限（#6-4），默认 100，可用 UPLOAD_DAILY_LIMIT 覆盖
     */
    @Value("${blog.upload-daily-limit:100}")
    private long dailyLimit;

    /**
     * 上传图片（已登录即可），返回 {url, id}。
     * 上传成功后落一条附件库记录（未分组）；id 以字符串返回避免 JS Long 精度丢失，
     * 前端只消费 url，不受影响。
     * 频率限制（#6-4）：每用户每日配额，超限拒绝（UPLOAD_DAILY_LIMIT_EXCEEDED）。
     */
    @PostMapping("/image")
    public Result<Map<String, String>> uploadImage(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam("file") MultipartFile file) {
        Long userId = JwtSubjects.userIdOf(jwt);
        assertDailyQuota(userId);
        String url = uploadService.uploadImage(file);
        Attachment attachment = attachmentService.recordUpload(userId, file, url);
        return Result.ok(Map.of("url", url, "id", String.valueOf(attachment.getId())));
    }

    /**
     * 每用户每日配额校验（Redis 计数，按自然日聚合，首次写入套 24h TTL 兜底过期）
     */
    private void assertDailyQuota(Long userId) {
        String key = DAILY_COUNT_KEY.formatted(userId, LocalDate.now());
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count == null) {
            return;
        }
        if (count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofDays(1));
        }
        if (count > dailyLimit) {
            throw new BusinessException(StatusCode.UPLOAD_DAILY_LIMIT_EXCEEDED);
        }
    }
}

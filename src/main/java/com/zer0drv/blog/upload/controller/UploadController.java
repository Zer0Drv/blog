package com.zer0drv.blog.upload.controller;

import com.zer0drv.blog.attachment.domain.Attachment;
import com.zer0drv.blog.attachment.service.AttachmentService;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.upload.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;
    private final AttachmentService attachmentService;

    /**
     * 上传图片（已登录即可），返回 {url, id}。
     * 上传成功后落一条附件库记录（未分组）；id 以字符串返回避免 JS Long 精度丢失，
     * 前端只消费 url，不受影响。
     */
    @PostMapping("/image")
    public Result<Map<String, String>> uploadImage(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam("file") MultipartFile file) {
        String url = uploadService.uploadImage(file);
        Attachment attachment = attachmentService.recordUpload(JwtSubjects.userIdOf(jwt), file, url);
        return Result.ok(Map.of("url", url, "id", String.valueOf(attachment.getId())));
    }
}

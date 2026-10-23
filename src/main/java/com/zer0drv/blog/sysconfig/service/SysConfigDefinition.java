package com.zer0drv.blog.sysconfig.service;

import java.util.ArrayList;
import java.util.List;

/** 受代码评审保护的白名单；配置中心不接受任意 Spring 属性或密钥。 */
public record SysConfigDefinition(String key, String env, String type, String group, String description,
                                  Object defaultValue, boolean hotReload, Long min, Long max,
                                  String regex, List<String> options) {
    public static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "webp");
    public static final List<SysConfigDefinition> ALL = definitions();

    private static List<SysConfigDefinition> definitions() {
        List<SysConfigDefinition> definitions = new ArrayList<>();
        String[] scenes = {"login", "register", "email-code", "password-reset", "comment", "article-password"};
        int[] thresholds = {5, 3, 5, 3, 10, 5};
        for (int i = 0; i < scenes.length; i++) {
            String prefix = "blog.captcha.scenes." + scenes[i];
            String env = "CAPTCHA_" + scenes[i].replace('-', '_').toUpperCase(java.util.Locale.ROOT);
            definitions.add(integer(prefix + ".threshold", env + "_THRESHOLD", "验证码", scenes[i] + " 触发阈值", thresholds[i], 1, 1000, true));
            definitions.add(integer(prefix + ".window-seconds", env + "_WINDOW_SECONDS", "验证码", scenes[i] + " 计数窗口（秒；新窗口生效）", 600, 60, 86400, true));
        }
        definitions.add(integer("blog.captcha.code-ttl-seconds", "CAPTCHA_CODE_TTL_SECONDS", "验证码", "新验证码有效期（秒）", 300, 30, 3600, true));
        definitions.add(integer("jwt.expiration-ms", "JWT_EXPIRATION_MS", "认证", "Access JWT 有效期（毫秒；最长 15 分钟；重启生效）", 900000, 300000, 900000, false));
        definitions.add(new SysConfigDefinition("blog.auth.cookie.same-site", "AUTH_COOKIE_SAME_SITE", "STRING", "认证", "认证 Cookie SameSite（None 必须启用 Secure；重启生效）", "Strict", false, null, null, "^(Strict|Lax|None)$", List.of("Strict", "Lax", "None")));
        definitions.add(integer("blog.upload-daily-limit", "UPLOAD_DAILY_LIMIT", "上传", "每用户每日上传次数上限", 100, 1, 100000, true));
        definitions.add(integer("blog.upload.max-bytes", "UPLOAD_MAX_BYTES", "上传", "单张图片大小上限（字节；不超过容器 5MB 上限）", 5242880, 1024, 5242880, true));
        definitions.add(new SysConfigDefinition("blog.upload.allowed-extensions", "UPLOAD_ALLOWED_EXTENSIONS", "JSON", "上传", "允许上传的图片扩展名（只能缩小安全白名单）", IMAGE_EXTENSIONS, true, null, null, "", IMAGE_EXTENSIONS));
        definitions.add(new SysConfigDefinition("blog.upload.storage", "UPLOAD_STORAGE", "STRING", "上传", "新上传的默认存储（重启生效；MINIO 需先通过环境配置启用客户端）", "LOCAL", false, null, null, "^(LOCAL|MINIO)$", List.of("LOCAL", "MINIO")));
        definitions.add(new SysConfigDefinition("blog.search.fulltext-enabled", "SEARCH_FULLTEXT_ENABLED", "BOOL", "搜索", "启用 MySQL 全文搜索（数据库须已有全文索引）", true, true, null, null, "", List.of()));
        return List.copyOf(definitions);
    }

    private static SysConfigDefinition integer(String key, String env, String group, String description, long value, long min, long max, boolean hotReload) {
        return new SysConfigDefinition(key, env, "INT", group, description, value, hotReload, min, max, "", List.of());
    }
}

package com.zer0drv.blog.site.service;

import java.util.Map;

/**
 * 站点配置读取/写入契约（P0：由 backend-C 实现，backend-B 仅依赖本接口编程）。
 * 键清单：site.name / site.description / site.logo / site.icp / site.footer /
 * site.base_url / comment.review_required
 *
 * @author Yoruhaki
 */
public interface SiteConfigService {

    /**
     * 读站点配置项；键不存在返回 defaultValue
     */
    String getValue(String key, String defaultValue);

    /**
     * 读布尔配置项；键不存在或值非 true/false 时返回 defaultValue
     */
    boolean getBool(String key, boolean defaultValue);

    /**
     * 全量配置（含非公开键）
     */
    Map<String, String> getAll();

    /**
     * 批量 upsert（仅允许已知键，未知键忽略）
     */
    void setAll(Map<String, String> values);
}

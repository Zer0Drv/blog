package com.zer0drv.blog.site.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.site.domain.SiteConfig;
import com.zer0drv.blog.site.mapper.SiteConfigMapper;
import com.zer0drv.blog.site.service.SiteConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点配置实现：DB 持久化 + ConcurrentHashMap 内存缓存（启动加载 + setAll 后刷新）。
 * 不依赖 Redis——配置量小且读多写少，缓存即全部已知键的最新值。
 * 键集合固定为 KNOWN_KEYS：setAll 对未知键静默忽略（防任意键注入）。
 *
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class SiteConfigServiceImpl implements SiteConfigService {

    /**
     * 已知配置键（与 V9 种子一致），setAll 只接受这些键
     */
    public static final Set<String> KNOWN_KEYS = Set.of(
            "site.name", "site.description", "site.logo", "site.icp", "site.footer",
            "site.base_url", "comment.review_required");

    /**
     * 公开接口（GET /site/config）允许外泄的白名单键；base_url 与审核开关不公开
     */
    public static final Set<String> PUBLIC_KEYS = Set.of(
            "site.name", "site.description", "site.logo", "site.icp", "site.footer");

    private final SiteConfigMapper siteConfigMapper;

    /**
     * 配置缓存：键 -> 最新值
     */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 启动加载缓存
     */
    @PostConstruct
    void init() {
        refresh();
    }

    @Override
    public String getValue(String key, String defaultValue) {
        String value = cache.get(key);
        return Objects.nonNull(value) ? value : defaultValue;
    }

    @Override
    public boolean getBool(String key, boolean defaultValue) {
        String value = cache.get(key);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    @Override
    public Map<String, String> getAll() {
        return Map.copyOf(cache);
    }

    /**
     * 公开白名单视图（只出 PUBLIC_KEYS）
     */
    public Map<String, String> getPublic() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            result.put(key, cache.getOrDefault(key, ""));
        }
        return result;
    }

    @Override
    public synchronized void setAll(Map<String, String> values) {
        if (Objects.isNull(values) || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            // 未知键忽略（键集合固定，防止写入任意键）
            if (Objects.isNull(key) || !KNOWN_KEYS.contains(key)) {
                continue;
            }
            String value = Objects.nonNull(entry.getValue()) ? entry.getValue() : "";
            SiteConfig existing = siteConfigMapper.selectOne(Wrappers.lambdaQuery(SiteConfig.class)
                    .eq(SiteConfig::getConfigKey, key));
            if (Objects.nonNull(existing)) {
                existing.setConfigValue(value);
                siteConfigMapper.updateById(existing);
            } else {
                SiteConfig created = new SiteConfig();
                created.setConfigKey(key);
                created.setConfigValue(value);
                siteConfigMapper.insert(created);
            }
        }
        // 写后刷新缓存，保证读立即一致
        refresh();
    }

    /**
     * 从 DB 全量重载缓存
     */
    private void refresh() {
        List<SiteConfig> rows = siteConfigMapper.selectList(Wrappers.lambdaQuery(SiteConfig.class));
        Map<String, String> loaded = new LinkedHashMap<>();
        for (SiteConfig row : rows) {
            loaded.put(row.getConfigKey(), Objects.nonNull(row.getConfigValue()) ? row.getConfigValue() : "");
        }
        cache.clear();
        cache.putAll(loaded);
    }
}

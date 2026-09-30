package com.zer0drv.blog.site;

import com.zer0drv.blog.site.domain.SiteConfig;
import com.zer0drv.blog.site.mapper.SiteConfigMapper;
import com.zer0drv.blog.site.service.impl.SiteConfigServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SiteConfigServiceImpl 纯单测：缓存加载 / 布尔解析 / 未知键忽略 / setAll 后刷新。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class SiteConfigServiceImplTest {

    @Mock
    private SiteConfigMapper siteConfigMapper;

    private SiteConfigServiceImpl service;

    private static SiteConfig row(String key, String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }

    @BeforeEach
    void setUp() {
        service = new SiteConfigServiceImpl(siteConfigMapper);
        // 启动加载：模拟 DB 中的 7 个种子键
        lenient().when(siteConfigMapper.selectList(any())).thenReturn(List.of(
                row("site.name", "Blog"),
                row("site.description", "记录与分享"),
                row("site.logo", ""),
                row("site.icp", ""),
                row("site.footer", ""),
                row("site.base_url", "http://localhost:5173"),
                row("comment.review_required", "false")));
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    @Test
    void getValueReadsFromCache() {
        assertEquals("Blog", service.getValue("site.name", "x"));
        // 已播种的空值键返回空串而非默认值
        assertEquals("", service.getValue("site.logo", "x"));
        // 未知键返回默认值
        assertEquals("fallback", service.getValue("no.such.key", "fallback"));
    }

    @Test
    void getBoolParsesTrueFalseOnly() {
        assertFalse(service.getBool("comment.review_required", true));
        assertTrue(service.getBool("no.such.key", true));
    }

    @Test
    void getAllReturnsFullCopy() {
        Map<String, String> all = service.getAll();
        assertEquals(7, all.size());
        assertEquals("http://localhost:5173", all.get("site.base_url"));
    }

    @Test
    void getPublicOnlyExposesWhitelistKeys() {
        Map<String, String> publicView = service.getPublic();
        assertEquals(5, publicView.size());
        assertTrue(publicView.containsKey("site.name"));
        assertFalse(publicView.containsKey("site.base_url"));
        assertFalse(publicView.containsKey("comment.review_required"));
    }

    @Test
    void setAllIgnoresUnknownKeysAndRefreshesCache() {
        when(siteConfigMapper.selectOne(any())).thenReturn(row("site.name", "Blog"));
        // setAll 后 refresh 重新查全量：模拟更新后的 DB 状态
        when(siteConfigMapper.selectList(any())).thenReturn(List.of(
                row("site.name", "新站名"),
                row("site.base_url", "http://localhost:5173")));

        service.setAll(Map.of(
                "site.name", "新站名",
                "unknown.key", "hack",
                "evil", "x"));

        // 已知键走 update；未知键完全不动 DB（selectOne 只被调用 1 次，即 site.name）
        ArgumentCaptor<SiteConfig> captor = ArgumentCaptor.forClass(SiteConfig.class);
        verify(siteConfigMapper).updateById(captor.capture());
        assertEquals("新站名", captor.getValue().getConfigValue());
        verify(siteConfigMapper, never()).insert(any(SiteConfig.class));
        // 缓存已刷新
        assertEquals("新站名", service.getValue("site.name", "x"));
        assertFalse(service.getAll().containsKey("unknown.key"));
    }

    @Test
    void setAllInsertsMissingKnownKey() {
        when(siteConfigMapper.selectOne(any())).thenReturn(null);
        service.setAll(Map.of("site.icp", "京ICP备xxx"));
        ArgumentCaptor<SiteConfig> captor = ArgumentCaptor.forClass(SiteConfig.class);
        verify(siteConfigMapper).insert(captor.capture());
        assertEquals("site.icp", captor.getValue().getConfigKey());
        assertEquals("京ICP备xxx", captor.getValue().getConfigValue());
    }
}

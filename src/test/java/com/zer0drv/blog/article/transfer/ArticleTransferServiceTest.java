package com.zer0drv.blog.article.transfer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zer0drv.blog.article.api.ArticleStatus;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.enums.EditorType;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleExportVO;
import com.zer0drv.blog.article.vo.ArticleImportResultVO;
import com.zer0drv.blog.category.domain.Category;
import com.zer0drv.blog.category.mapper.CategoryMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.tag.domain.Tag;
import com.zer0drv.blog.tag.mapper.TagMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleTransferService 纯单测（issue #16）：
 * 导出——front matter 元数据完整性、可见性同详情、format 校验、批量 zip（权限 / 条目名去重）；
 * 导入——front matter 白名单填充（标签自动创建 / 分类按名匹配 / date 回填 publish_time）、
 * status 与未知字段警告、无 front matter 回退 H1、边界（空文件 / 超大 / 非法扩展名 / 非法 YAML）、
 * zip 批量（逐条容错 / Zip Slip / 条目与总量上限 / 空包与损坏包）、外链图片待转存清单。
 * MP 的 lambdaQuery 需实体 TableInfo，参照 ArticleServiceImplTest 手动初始化。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class ArticleTransferServiceTest {

    @Mock
    private ArticleService articleService;
    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private ArticleTagMapper articleTagMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private CategoryMapper categoryMapper;

    private ArticleTransferService transferService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo（lambdaQuery 列缓存）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, ArticleTag.class);
        TableInfoHelper.initTableInfo(assistant, Tag.class);
        TableInfoHelper.initTableInfo(assistant, Category.class);
    }

    @BeforeEach
    void setUp() {
        transferService = new ArticleTransferService(
                articleService, articleMapper, articleTagMapper, tagMapper, categoryMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt jwtOf(long userId, String... roles) {
        Jwt jwt = new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of(roles)));
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        return jwt;
    }

    private static Article article(long id, long authorId, String status, String editorType) {
        Article article = new Article();
        article.setId(id);
        article.setTitle("标题" + id);
        article.setSlug("post-" + id);
        article.setSummary("摘要" + id);
        article.setContent("# 正文" + id);
        article.setEditorType(editorType);
        article.setCover("https://example.com/cover" + id + ".png");
        article.setAuthorId(authorId);
        article.setStatus(status);
        article.setCreateTime(LocalDateTime.of(2026, 1, 1, 8, 0));
        article.setUpdateTime(LocalDateTime.of(2026, 1, 2, 9, 0));
        if (ArticleStatus.PUBLISHED.name().equals(status)) {
            article.setPublishTime(LocalDateTime.of(2026, 1, 1, 12, 0));
        }
        return article;
    }

    private static MockMultipartFile mdFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zipBytes(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /* ================================ 导出 ================================ */

    @Test
    void exportArticle_notExist_throwsNotExist() {
        when(articleMapper.selectById(99L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> transferService.exportArticle(99L, null, null));
        assertEquals("40030", e.getCode());
    }

    @Test
    void exportArticle_draftInvisibleToAnonymous() {
        when(articleMapper.selectById(1L)).thenReturn(article(1L, 7L, "DRAFT", "MARKDOWN"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> transferService.exportArticle(1L, null, null));
        assertEquals("40030", e.getCode());
    }

    @Test
    void exportArticle_frontMatterContainsAllMeta() {
        Article published = article(1L, 7L, "PUBLISHED", "MARKDOWN");
        published.setCategoryId(5L);
        when(articleMapper.selectById(1L)).thenReturn(published);
        ArticleTag relation = new ArticleTag();
        relation.setArticleId(1L);
        relation.setTagId(11L);
        when(articleTagMapper.selectList(any())).thenReturn(List.of(relation));
        Tag tag = new Tag();
        tag.setId(11L);
        tag.setName("随笔");
        when(tagMapper.selectByIds(anyList())).thenReturn(List.of(tag));
        Category category = new Category();
        category.setId(5L);
        category.setName("生活");
        when(categoryMapper.selectById(5L)).thenReturn(category);

        ArticleExportVO export = transferService.exportArticle(1L, null, null);

        assertEquals("md", export.extension());
        String body = new String(export.body(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("---\n"), body);
        assertTrue(body.contains("title: 标题1\n"), body);
        // slug 固定链接随 front matter 导出（issue #14/#16 协同）
        assertTrue(body.contains("slug: post-1\n"), body);
        // date 取 publishTime（优先于 createTime），snakeyaml 对时间戳样式字符串加引号
        assertTrue(body.contains("date: '2026-01-01T12:00:00'\n"), body);
        assertTrue(body.contains("updated: '2026-01-02T09:00:00'\n"), body);
        assertTrue(body.contains("status: PUBLISHED\n"), body);
        assertTrue(body.contains("tags:\n- 随笔\n"), body);
        assertTrue(body.contains("category: 生活\n"), body);
        assertTrue(body.contains("summary: 摘要1\n"), body);
        assertTrue(body.contains("cover: https://example.com/cover1.png\n"), body);
        assertTrue(body.endsWith("---\n\n# 正文1"), body);
    }

    @Test
    void exportArticle_emptyOptionalFieldsOmitted() {
        Article draft = article(2L, 7L, "DRAFT", "MARKDOWN");
        draft.setSummary("");
        draft.setCover("");
        when(articleMapper.selectById(2L)).thenReturn(draft);
        when(articleTagMapper.selectList(any())).thenReturn(List.of());

        // 作者本人可导出草稿；date 回退 createTime
        ArticleExportVO export = transferService.exportArticle(2L, null, jwtOf(7L, "ROLE_AUTHOR"));
        String body = new String(export.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("date: '2026-01-01T08:00:00'\n"), body);
        assertTrue(body.contains("status: DRAFT\n"), body);
        assertFalse(body.contains("tags:"), body);
        assertFalse(body.contains("category:"), body);
        assertFalse(body.contains("summary:"), body);
        assertFalse(body.contains("cover:"), body);
    }

    @Test
    void exportArticle_invalidFormatRejected() {
        when(articleMapper.selectById(1L)).thenReturn(article(1L, 7L, "PUBLISHED", "MARKDOWN"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> transferService.exportArticle(1L, "pdf", null));
        assertEquals("40023", e.getCode());
        assertEquals("导出格式仅支持 md / html", e.getMessage());
    }

    @Test
    void exportArticlesZip_emptyOrTooManyIdsRejected() {
        BusinessException empty = assertThrows(BusinessException.class,
                () -> transferService.exportArticlesZip(List.of(), jwtOf(1L, "ROLE_ADMIN")));
        assertEquals("40023", empty.getCode());

        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 201; i++) {
            tooMany.add(i);
        }
        BusinessException overflow = assertThrows(BusinessException.class,
                () -> transferService.exportArticlesZip(tooMany, jwtOf(1L, "ROLE_ADMIN")));
        assertEquals("40023", overflow.getCode());
    }

    @Test
    void exportArticlesZip_authorCannotExportOthers() {
        when(articleMapper.selectByIds(anyList()))
                .thenReturn(List.of(article(1L, 7L, "PUBLISHED", "MARKDOWN")));
        // 登录用户 8（AUTHOR）导用户 7 的文章：与不存在同语义
        BusinessException e = assertThrows(BusinessException.class,
                () -> transferService.exportArticlesZip(List.of(1L), jwtOf(8L, "ROLE_AUTHOR")));
        assertEquals("40030", e.getCode());
    }

    @Test
    void exportArticlesZip_adminExportsAllWithUniqueEntryNames() throws IOException {
        Article first = article(1L, 7L, "PUBLISHED", "MARKDOWN");
        Article second = article(2L, 8L, "DRAFT", "MARKDOWN");
        // 同名同格式文章：zip 条目名冲突时追加 -<id>
        second.setTitle(first.getTitle());
        when(articleMapper.selectByIds(anyList())).thenReturn(List.of(first, second));
        when(articleTagMapper.selectList(any())).thenReturn(List.of());

        ArticleExportVO zip = transferService.exportArticlesZip(List.of(1L, 2L), jwtOf(1L, "ROLE_ADMIN"));

        assertEquals("zip", zip.extension());
        Map<String, String> entries = unzip(zip.body());
        assertEquals(2, entries.size());
        // 同名第二篇追加 -2；混合格式时各按 editorType 默认扩展名（本例同为 md）
        assertTrue(entries.containsKey("标题1.md"), entries.keySet().toString());
        assertTrue(entries.containsKey("标题1-2.md"), entries.keySet().toString());
        assertTrue(entries.get("标题1.md").contains("status: PUBLISHED"));
        assertTrue(entries.get("标题1-2.md").contains("status: DRAFT"));
    }

    @Test
    void exportArticlesZip_authorExportsOwnOnly() {
        when(articleMapper.selectByIds(anyList()))
                .thenReturn(List.of(article(3L, 7L, "PUBLISHED", "MARKDOWN")));
        when(articleTagMapper.selectList(any())).thenReturn(List.of());
        ArticleExportVO zip = transferService.exportArticlesZip(List.of(3L), jwtOf(7L, "ROLE_AUTHOR"));
        assertEquals("zip", zip.extension());
    }

    /* ================================ 导入 ================================ */

    @Test
    void importSingle_frontMatterFillsMeta() {
        when(articleService.create(any(), any())).thenReturn(42L);
        Article created = new Article();
        created.setId(42L);
        when(articleMapper.selectById(42L)).thenReturn(created);
        // 标签按名未命中 → 自动创建并回写 id；分类按名命中
        when(tagMapper.selectOne(any())).thenReturn(null);
        when(tagMapper.insert(any(Tag.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Tag.class).setId(99L);
            return 1;
        });
        Category category = new Category();
        category.setId(5L);
        when(categoryMapper.selectList(any())).thenReturn(List.of(category));
        String content = "---\n"
                + "title: 元数据标题\n"
                + "date: '2025-05-06T07:08:09'\n"
                + "tags:\n- 旧标签\n- 新标签\n"
                + "category: 生活\n"
                + "summary: 导入摘要\n"
                + "cover: https://example.com/c.png\n"
                + "status: PUBLISHED\n"
                + "layout: post\n"
                + "---\n\n"
                + "# 正文标题\n\n正文";

        ArticleImportResultVO result = transferService.importArticles(
                mdFile("post.md", content), jwtOf(7L, "ROLE_AUTHOR"));

        assertEquals(1, result.imported().size());
        assertTrue(result.failed().isEmpty());
        ArticleImportResultVO.ImportedItem item = result.imported().get(0);
        assertEquals(42L, item.id());
        assertEquals("元数据标题", item.title());
        // status 忽略提示 + 未知字段汇总提示
        assertTrue(item.warnings().stream().anyMatch(w -> w.contains("status") && w.contains("草稿")),
                item.warnings().toString());
        assertTrue(item.warnings().stream().anyMatch(w -> w.contains("layout")),
                item.warnings().toString());

        // create 收到完整元数据且固定 DRAFT；front matter 已有标题时正文 H1 保留（round-trip 保真）
        ArgumentCaptor<ArticleSaveDTO> captor = ArgumentCaptor.forClass(ArticleSaveDTO.class);
        verify(articleService).create(captor.capture(), any());
        ArticleSaveDTO dto = captor.getValue();
        assertEquals("元数据标题", dto.getTitle());
        assertEquals("MARKDOWN", dto.getEditorType());
        assertEquals("DRAFT", dto.getStatus());
        assertEquals("导入摘要", dto.getSummary());
        assertEquals("https://example.com/c.png", dto.getCover());
        assertEquals(5L, dto.getCategoryId());
        assertEquals(List.of(99L, 99L), dto.getTagIds());
        assertTrue(dto.getContent().contains("# 正文标题"), dto.getContent());

        // date 回填 publish_time（截断到秒）
        ArgumentCaptor<Article> articleCaptor = ArgumentCaptor.forClass(Article.class);
        verify(articleMapper).updateById(articleCaptor.capture());
        assertEquals(LocalDateTime.of(2025, 5, 6, 7, 8, 9), articleCaptor.getValue().getPublishTime());
    }

    @Test
    void importSingle_tagAutoCreatedWhenMissing() {
        when(articleService.create(any(), any())).thenReturn(43L);
        when(tagMapper.selectOne(any())).thenReturn(null);
        when(tagMapper.insert(any(Tag.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Tag.class).setId(99L);
            return 1;
        });

        ArticleImportResultVO result = transferService.importArticles(
                mdFile("t.md", "---\ntitle: t\ntags: [新标签]\n---\n\n正文"), jwtOf(7L, "ROLE_AUTHOR"));

        assertEquals(1, result.imported().size());
        ArgumentCaptor<Tag> tagCaptor = ArgumentCaptor.forClass(Tag.class);
        verify(tagMapper).insert(tagCaptor.capture());
        assertEquals("新标签", tagCaptor.getValue().getName());
        ArgumentCaptor<ArticleSaveDTO> captor = ArgumentCaptor.forClass(ArticleSaveDTO.class);
        verify(articleService).create(captor.capture(), any());
        assertEquals(List.of(99L), captor.getValue().getTagIds());
    }

    @Test
    void importSingle_slugPassedThroughToCreate() {
        when(articleService.create(any(), any())).thenReturn(48L);

        ArticleImportResultVO result = transferService.importArticles(
                mdFile("s.md", "---\ntitle: t\nslug: my-old-post\n---\n\n正文"), jwtOf(7L, "ROLE_AUTHOR"));

        assertEquals(1, result.imported().size());
        ArgumentCaptor<ArticleSaveDTO> captor = ArgumentCaptor.forClass(ArticleSaveDTO.class);
        verify(articleService).create(captor.capture(), any());
        // slug 透传创建链路（格式 / 唯一校验在 create 内与编辑器保存同一套）
        assertEquals("my-old-post", captor.getValue().getSlug());
    }

    @Test
    void importSingle_categoryNotExist_warnedAndSkipped() {
        when(articleService.create(any(), any())).thenReturn(44L);
        when(categoryMapper.selectList(any())).thenReturn(List.of());

        ArticleImportResultVO result = transferService.importArticles(
                mdFile("c.md", "---\ntitle: t\ncategory: 不存在分类\n---\n\n正文"), jwtOf(7L, "ROLE_AUTHOR"));

        ArticleImportResultVO.ImportedItem item = result.imported().get(0);
        assertTrue(item.warnings().stream().anyMatch(w -> w.contains("不存在分类")), item.warnings().toString());
        ArgumentCaptor<ArticleSaveDTO> captor = ArgumentCaptor.forClass(ArticleSaveDTO.class);
        verify(articleService).create(captor.capture(), any());
        assertNull(captor.getValue().getCategoryId());
    }

    @Test
    void importSingle_noFrontMatter_fallsBackToH1() {
        when(articleService.create(any(), any())).thenReturn(45L);

        ArticleImportResultVO result = transferService.importArticles(
                mdFile("n.md", "# 标题行\n\n第一行正文"), jwtOf(7L, "ROLE_AUTHOR"));

        ArgumentCaptor<ArticleSaveDTO> captor = ArgumentCaptor.forClass(ArticleSaveDTO.class);
        verify(articleService).create(captor.capture(), any());
        assertEquals("标题行", captor.getValue().getTitle());
        // H1 行从正文移除
        assertFalse(captor.getValue().getContent().contains("# 标题行"));
        assertEquals(45L, result.imported().get(0).id());
        assertTrue(result.imported().get(0).warnings().isEmpty());
    }

    @Test
    void importSingle_invalidFrontMatter_rejected() {
        BusinessException e = assertThrows(BusinessException.class, () ->
                transferService.importArticles(mdFile("bad.md", "---\ntitle: [未闭合\n---\n正文"),
                        jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("40023", e.getCode());
        assertTrue(e.getMessage().startsWith("front matter 解析失败"));
    }

    @Test
    void importSingle_invalidDate_warnedAndNotApplied() {
        when(articleService.create(any(), any())).thenReturn(46L);

        ArticleImportResultVO result = transferService.importArticles(
                mdFile("d.md", "---\ntitle: t\ndate: 不是日期\n---\n\n正文"), jwtOf(7L, "ROLE_AUTHOR"));

        assertTrue(result.imported().get(0).warnings().stream()
                .anyMatch(w -> w.contains("date")), result.imported().get(0).warnings().toString());
        verify(articleMapper, never()).updateById(any(Article.class));
    }

    @Test
    void importSingle_boundaryFailures() {
        // 空文件
        MockMultipartFile empty = new MockMultipartFile("file", "a.md", "text/markdown", new byte[0]);
        BusinessException emptyEx = assertThrows(BusinessException.class,
                () -> transferService.importArticles(empty, jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("导入文件不能为空", emptyEx.getMessage());

        // 超大文件（>2MB）
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        MockMultipartFile oversize = new MockMultipartFile("file", "big.md", "text/markdown", big);
        BusinessException oversizeEx = assertThrows(BusinessException.class,
                () -> transferService.importArticles(oversize, jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("文件过大，最大 2MB", oversizeEx.getMessage());

        // 非法扩展名
        BusinessException extEx = assertThrows(BusinessException.class, () ->
                transferService.importArticles(mdFile("evil.exe", "MZ"), jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("仅支持 .md/.markdown/.txt/.html 文件", extEx.getMessage());

        // 入参为空 / 无内容
        BusinessException nullEx = assertThrows(BusinessException.class,
                () -> transferService.importArticles(null, jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("导入文件不能为空", nullEx.getMessage());
    }

    @Test
    void importSingle_collectsExternalImages() {
        when(articleService.create(any(), any())).thenReturn(47L);
        String content = "# 图文\n\n![图](https://a.com/1.png)\n\n<img src=\"https://b.com/2.jpg\">\n"
                + "![重复](https://a.com/1.png)\n![相对](/uploads/local.png)";

        ArticleImportResultVO result = transferService.importArticles(
                mdFile("img.md", content), jwtOf(7L, "ROLE_AUTHOR"));

        // 去重且仅外链（相对路径 / 站内地址不计入待转存清单）
        assertEquals(List.of("https://a.com/1.png", "https://b.com/2.jpg"),
                result.imported().get(0).externalImages());
    }

    @Test
    void importZip_mixedResultsCollected() throws IOException {
        when(articleService.create(any(), any())).thenReturn(50L);
        // LinkedHashMap 保证 zip 条目顺序，failed 明细按条目序断言
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("good.md", "# 好文章\n\n正文");
        entries.put("bad.exe", "MZ");
        entries.put("empty.md", "");
        byte[] zip = zipBytes(entries);

        ArticleImportResultVO result = transferService.importArticles(
                new MockMultipartFile("file", "batch.zip", "application/zip", zip), jwtOf(7L, "ROLE_AUTHOR"));

        assertEquals(1, result.imported().size());
        assertEquals(50L, result.imported().get(0).id());
        assertEquals(2, result.failed().size());
        assertEquals("bad.exe", result.failed().get(0).name());
        assertEquals("仅支持 .md/.markdown/.txt/.html 文件", result.failed().get(0).message());
        assertEquals("empty.md", result.failed().get(1).name());
        assertEquals("导入文件不能为空", result.failed().get(1).message());
    }

    @Test
    void importZip_zipSlipRejected() throws IOException {
        byte[] zip = zipBytes(Map.of("../evil.md", "# 穿越\n\n正文"));
        BusinessException e = assertThrows(BusinessException.class, () ->
                transferService.importArticles(new MockMultipartFile("file", "evil.zip", null, zip),
                        jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("40023", e.getCode());
        assertTrue(e.getMessage().contains("非法路径"), e.getMessage());
    }

    @Test
    void importZip_tooManyEntriesRejected() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 51; i++) {
                zos.putNextEntry(new ZipEntry("a" + i + ".md"));
                zos.write("# 正文".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        BusinessException e = assertThrows(BusinessException.class, () ->
                transferService.importArticles(new MockMultipartFile("file", "many.zip", null,
                        baos.toByteArray()), jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("40023", e.getCode());
        assertTrue(e.getMessage().contains("最多"), e.getMessage());
    }

    @Test
    void importZip_emptyAndCorruptRejected() {
        // 空 zip（合法但无条目）
        BusinessException empty = assertThrows(BusinessException.class, () ->
                transferService.importArticles(new MockMultipartFile("file", "empty.zip", null,
                        zipBytes(Map.of())), jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("zip 中没有可导入的文件", empty.getMessage());

        // 非 zip 内容
        BusinessException corrupt = assertThrows(BusinessException.class, () ->
                transferService.importArticles(new MockMultipartFile("file", "fake.zip", null,
                        "not a zip".getBytes(StandardCharsets.UTF_8)), jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("zip 中没有可导入的文件", corrupt.getMessage());
    }

    @Test
    void importZip_oversizePackageRejected() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile oversize = new MockMultipartFile("file", "big.zip", null, big);
        BusinessException e = assertThrows(BusinessException.class,
                () -> transferService.importArticles(oversize, jwtOf(7L, "ROLE_AUTHOR")));
        assertEquals("zip 文件过大，最大 5MB", e.getMessage());
    }

    @Test
    void importZip_oversizeEntryFailsIndividually() throws IOException {
        when(articleService.create(any(), any())).thenReturn(60L);
        byte[] bigContent = new byte[2 * 1024 * 1024 + 1];
        Arrays.fill(bigContent, (byte) 'a');
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("big.md"));
            zos.write(bigContent);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("ok.md"));
            zos.write("# 正常\n\n正文".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        ArticleImportResultVO result = transferService.importArticles(
                new MockMultipartFile("file", "mix.zip", null, baos.toByteArray()), jwtOf(7L, "ROLE_AUTHOR"));

        assertEquals(1, result.imported().size());
        assertEquals(1, result.failed().size());
        assertEquals("big.md", result.failed().get(0).name());
        assertEquals("文件过大，最大 2MB", result.failed().get(0).message());
    }

    @Test
    void importSingle_htmlImportKeepsRichtext() {
        when(articleService.create(any(), any())).thenReturn(61L);

        ArticleImportResultVO result = transferService.importArticles(
                new MockMultipartFile("file", "page.html", "text/html",
                        "<p>富文本</p>".getBytes(StandardCharsets.UTF_8)), jwtOf(7L, "ROLE_AUTHOR"));

        ArgumentCaptor<ArticleSaveDTO> captor = ArgumentCaptor.forClass(ArticleSaveDTO.class);
        verify(articleService).create(captor.capture(), any());
        assertEquals(EditorType.RICHTEXT.name(), captor.getValue().getEditorType());
        assertEquals("<p>富文本</p>", captor.getValue().getContent());
        assertEquals(61L, result.imported().get(0).id());
        assertNotNull(result.failed());
    }
}

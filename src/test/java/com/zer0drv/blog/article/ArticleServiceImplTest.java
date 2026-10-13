package com.zer0drv.blog.article;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.api.ArticleCascade;
import com.zer0drv.blog.article.api.ArticleCommentStatsPort;
import com.zer0drv.blog.article.api.ArticleInteractionPort;
import com.zer0drv.blog.article.api.ArticleInteractionStats;
import com.zer0drv.blog.article.api.ArticleNotificationPort;
import com.zer0drv.blog.article.api.ArticleStatus;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.domain.ArticleVersion;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.enums.EditorType;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.article.mapper.ArticleVersionMapper;
import com.zer0drv.blog.article.service.impl.ArticleServiceImpl;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.category.mapper.CategoryMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.tag.mapper.TagMapper;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import io.github.linpeilie.Converter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * article 系 P0 纯单测：版本快照 / 恢复、自动保存（mock Redis）、定时发布规则、
 * content_text 清洗、全文搜索与 LIKE 兜底两路径。
 * MP 继承方法（getById/updateById/save/page）用 spy + doReturn 桩掉。
 *
 * @author Yoruhaki
 */
@ExtendWith(MockitoExtension.class)
class ArticleServiceImplTest {

    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private ArticleTagMapper articleTagMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private UserService userService;
    @Mock
    private Converter converter;
    @Mock
    private ArticleInteractionPort articleInteractionPort;
    @Mock
    private ArticleCommentStatsPort articleCommentStatsPort;
    @Mock
    private ArticleVersionMapper articleVersionMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ArticleCascade commentCascade;
    @Mock
    private ArticleCascade interactionCascade;
    @Mock
    private ArticleNotificationPort articleNotificationPort;
    @Mock
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ArticleServiceImpl articleService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 SqlSessionFactory：手动初始化实体 TableInfo，
        // 否则 LambdaQueryWrapper(实体::字段) 找不到 lambda 列缓存（参照 SensitiveWordServiceImplTest）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, ArticleTag.class);
        TableInfoHelper.initTableInfo(assistant, ArticleVersion.class);
    }

    @BeforeEach
    void setUp() {
        // 注意构造参数顺序与 @RequiredArgsConstructor 字段声明一致（baseMapper 由 MP 注入，此处反射补）
        // 跨模块级联清场以 ArticleCascade SPI mock 顶替（issue #24 第三步，外模块 Mapper 已退出编排）
        // 详情页互动/评论计数经 article.api 读端口 mock 顶替（issue #34 第三步，三 Mapper 已退出装配）
        articleService = spy(new ArticleServiceImpl(
                articleTagMapper, tagMapper, categoryMapper, userService, converter,
                articleInteractionPort, articleCommentStatsPort,
                articleVersionMapper, stringRedisTemplate, objectMapper,
                List.of(commentCascade, interactionCascade), articleNotificationPort,
                passwordEncoder));
        ReflectionTestUtils.setField(articleService, "baseMapper", articleMapper);
        ReflectionTestUtils.setField(articleService, "fulltextEnabled", false);
    }

    @AfterEach
    void tearDown() {
        // jwtOf 会向安全上下文写入认证，逐个用例清理防串扰
        SecurityContextHolder.clearContext();
    }

    private static Jwt jwtOf(long userId, String... roles) {
        Jwt jwt = new Jwt("tk", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", String.valueOf(userId), "roles", List.of(roles)));
        // 与生产链路对齐：isAdmin 以安全上下文权限为准（由 DatabaseRoleJwtAuthenticationConverter
        // 实时读库装配），单测在此把 roles 同步进安全上下文
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        return jwt;
    }

    private static Article draftArticle(long id, long authorId) {
        Article article = new Article();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle("旧标题");
        article.setSummary("旧摘要");
        article.setContent("旧正文");
        article.setEditorType(EditorType.MARKDOWN.name());
        article.setCover("");
        article.setStatus(ArticleStatus.DRAFT.name());
        return article;
    }

    private static ArticleSaveDTO saveDto(String title, String content, String status) {
        ArticleSaveDTO dto = new ArticleSaveDTO();
        dto.setTitle(title);
        dto.setContent(content);
        dto.setEditorType(EditorType.MARKDOWN.name());
        dto.setStatus(status);
        return dto;
    }

    @Test
    void update_snapshotsOldRowAsVersionOne() {
        Article old = draftArticle(10L, 1L);
        doReturn(old).when(articleService).getById(10L);
        when(articleVersionMapper.selectOne(any())).thenReturn(null);
        doReturn(true).when(articleService).updateById(any(Article.class));

        articleService.update(10L, saveDto("新标题", "新正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(articleVersionMapper).insert(captor.capture());
        ArticleVersion snapshot = captor.getValue();
        // 快照存「更新前的旧行」，首版本号为 1
        assertEquals(1, snapshot.getVersion());
        assertEquals("旧标题", snapshot.getTitle());
        assertEquals("旧正文", snapshot.getContent());
        assertEquals(10L, snapshot.getArticleId());
    }

    @Test
    void update_secondSnapshotIncrementsVersion() {
        Article old = draftArticle(10L, 1L);
        doReturn(old).when(articleService).getById(10L);
        ArticleVersion latest = new ArticleVersion();
        latest.setVersion(7);
        when(articleVersionMapper.selectOne(any())).thenReturn(latest);
        doReturn(true).when(articleService).updateById(any(Article.class));

        articleService.update(10L, saveDto("新标题", "新正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(articleVersionMapper).insert(captor.capture());
        assertEquals(8, captor.getValue().getVersion());
    }

    @Test
    void update_maintainsContentTextByCleaningHtmlAndMarkdown() {
        Article old = draftArticle(10L, 1L);
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));

        articleService.update(10L, saveDto("t", "<p>你好</p> # 标题 *加粗* [链接](http://a)", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("你好 标题 加粗 链接 http://a", captor.getValue().getContentText());
    }

    @Test
    void update_publishWithFuturePublishTimeSchedules() {
        Article old = draftArticle(10L, 1L);
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        LocalDateTime future = LocalDateTime.now().plusHours(1);
        ArticleSaveDTO dto = saveDto("t", "c", "PUBLISHED");
        dto.setPublishTime(future);

        articleService.update(10L, dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals(future, captor.getValue().getPublishTime());
    }

    @Test
    void update_publishTimeTooSoonThrowsParamInvalid() {
        Article old = draftArticle(10L, 1L);
        doReturn(old).when(articleService).getById(10L);
        ArticleSaveDTO dto = saveDto("t", "c", "PUBLISHED");
        dto.setPublishTime(LocalDateTime.now().plusSeconds(10));

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.update(10L, dto, jwtOf(1L)));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), e.getCode());
        assertEquals("定时发布时间必须晚于当前时间", e.getMessage());
    }

    @Test
    void update_liveArticleIgnoresDtoPublishTime() {
        Article old = draftArticle(10L, 1L);
        old.setStatus(ArticleStatus.PUBLISHED.name());
        // 已上线：publish_time <= now
        LocalDateTime liveSince = LocalDateTime.now().minusDays(1);
        old.setPublishTime(liveSince);
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        ArticleSaveDTO dto = saveDto("t", "c", "PUBLISHED");
        dto.setPublishTime(LocalDateTime.now().plusDays(3));

        articleService.update(10L, dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        // 已上线文章不可改档期，publishTime 保持原值
        assertEquals(liveSince, captor.getValue().getPublishTime());
    }

    @Test
    void update_successDeletesAutosaveDraft() {
        Article old = draftArticle(10L, 1L);
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));

        articleService.update(10L, saveDto("t", "c", "DRAFT"), jwtOf(1L));

        verify(stringRedisTemplate).delete("blog:autosave:10:1");
    }

    @Test
    void restoreVersion_snapshotsCurrentRowThenOverwrites() {
        Article current = draftArticle(10L, 1L);
        doReturn(current).when(articleService).getById(10L);
        ArticleVersion target = new ArticleVersion();
        target.setArticleId(10L);
        target.setVersion(3);
        target.setTitle("历史标题");
        target.setSummary("历史摘要");
        target.setContent("<b>历史正文</b>");
        target.setEditorType(EditorType.RICHTEXT.name());
        target.setCover("cover.png");
        target.setCategoryId(5L);
        when(articleVersionMapper.selectOne(any()))
                // 第一次调用是 requireVersion 取目标版本，第二次是 snapshotVersion 取当前最大版本
                .thenReturn(target, (ArticleVersion) null);
        doReturn(true).when(articleService).updateById(any(Article.class));

        articleService.restoreVersion(10L, 3, jwtOf(1L));

        // 恢复也留痕：先快照当前行
        ArgumentCaptor<ArticleVersion> snapshotCaptor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(articleVersionMapper).insert(snapshotCaptor.capture());
        assertEquals("旧标题", snapshotCaptor.getValue().getTitle());
        ArgumentCaptor<Article> articleCaptor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(articleCaptor.capture());
        Article restored = articleCaptor.getValue();
        assertEquals("历史标题", restored.getTitle());
        assertEquals("<b>历史正文</b>", restored.getContent());
        assertEquals("历史正文", restored.getContentText());
        assertEquals(EditorType.RICHTEXT.name(), restored.getEditorType());
        assertEquals(5L, restored.getCategoryId());
        // status / publishTime 不动
        assertEquals(ArticleStatus.DRAFT.name(), restored.getStatus());
        assertNull(restored.getPublishTime());
    }

    @Test
    void getVersion_missingThrowsArticleVersionNotExist() {
        Article current = draftArticle(10L, 1L);
        doReturn(current).when(articleService).getById(10L);
        when(articleVersionMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.getVersion(10L, 99, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_VERSION_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void versionEndpoints_rejectNonAuthor() {
        Article current = draftArticle(10L, 1L);
        doReturn(current).when(articleService).getById(10L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.listVersions(10L, jwtOf(2L)));
        assertEquals(StatusCode.NOT_AUTHOR.getCode(), e.getCode());
        // ADMIN 放行
        articleService.listVersions(10L, jwtOf(2L, "ROLE_" + UserRole.ADMIN.name()));
    }

    @Test
    void saveAutosave_writesRedisWithTtlAndReturnsSavedAt() {
        Article article = draftArticle(10L, 1L);
        doReturn(article).when(articleService).getById(10L);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(ops);
        AutosaveDTO dto = new AutosaveDTO();
        dto.setTitle("草稿标题");
        dto.setContent("草稿正文");

        Map<String, Object> result = articleService.saveAutosave(10L, dto, jwtOf(1L));

        assertTrue(result.containsKey("savedAt"));
        verify(ops).set(eq("blog:autosave:10:1"), anyString(), eq(Duration.ofHours(2)));
    }

    @Test
    void getAutosave_missingReturnsExistsFalse() {
        Article article = draftArticle(10L, 1L);
        doReturn(article).when(articleService).getById(10L);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blog:autosave:10:1")).thenReturn(null);

        Map<String, Object> result = articleService.getAutosave(10L, jwtOf(1L));

        assertEquals(false, result.get("exists"));
    }

    @Test
    void getAutosave_existingReturnsDraftFields() {
        Article article = draftArticle(10L, 1L);
        doReturn(article).when(articleService).getById(10L);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blog:autosave:10:1")).thenReturn(
                "{\"title\":\"草稿标题\",\"content\":\"草稿正文\",\"savedAt\":123}");

        Map<String, Object> result = articleService.getAutosave(10L, jwtOf(1L));

        assertEquals(true, result.get("exists"));
        assertEquals("草稿标题", result.get("title"));
        assertEquals("草稿正文", result.get("content"));
    }

    @Test
    void search_blankKeywordThrowsParamInvalid() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.search("  ", 1, 10));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    void search_fulltextDisabledFallsBackToLike() {
        doReturn(new Page<Article>(1, 10)).when(articleService).page(any(Page.class), any(Wrapper.class));

        articleService.search("关键字", 1, 10);

        // LIKE 兜底：走 MP page，不触发 MATCH...AGAINST 自定义 SQL
        verify(articleMapper, never()).searchByFulltext(any(), anyString());
        verify(articleService).page(any(Page.class), any(Wrapper.class));
    }

    @Test
    void search_fulltextEnabledUsesMatchAgainst() {
        ReflectionTestUtils.setField(articleService, "fulltextEnabled", true);
        when(articleMapper.searchByFulltext(any(), eq("关键字"))).thenReturn(new Page<>(1, 10));

        articleService.search("关键字", 1, 10);

        verify(articleMapper).searchByFulltext(any(), eq("关键字"));
        verify(articleService, never()).page(any(Page.class), any(Wrapper.class));
    }

    @Test
    void restore_requiresTrashedRowAndOwnership() {
        // 不在回收站（deleted=0 或不存在）→ ARTICLE_NOT_EXIST
        when(articleMapper.selectDeletedById(anyLong())).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.restore(10L, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());

        // 非作者不可恢复
        Article trashed = draftArticle(10L, 1L);
        when(articleMapper.selectDeletedById(10L)).thenReturn(trashed);
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> articleService.restore(10L, jwtOf(2L)));
        assertEquals(StatusCode.NOT_AUTHOR.getCode(), e2.getCode());

        // 作者恢复：回草稿
        articleService.restore(10L, jwtOf(1L));
        verify(articleMapper).restoreDeleted(10L);
    }

    @Test
    void assemble_fillsVisibleFlagFromVisibilityPredicate() {
        // 三态：已发布到点 = 可见；已下架 = 不可见；定时中（PUBLISHED 但 publish_time 在未来）= 不可见。
        // 可见性谓词唯一出处 ArticleVisibility，装配层只转发不另算
        Article published = draftArticle(1L, 7L);
        published.setStatus(ArticleStatus.PUBLISHED.name());
        published.setPublishTime(LocalDateTime.now().minusMinutes(1));
        Article offline = draftArticle(2L, 7L);
        offline.setStatus(ArticleStatus.OFFLINE.name());
        offline.setPublishTime(LocalDateTime.now().minusDays(1));
        Article scheduled = draftArticle(3L, 7L);
        scheduled.setStatus(ArticleStatus.PUBLISHED.name());
        scheduled.setPublishTime(LocalDateTime.now().plusHours(1));
        when(converter.convert(any(Article.class), eq(ArticleListVO.class))).thenAnswer(invocation -> {
            ArticleListVO vo = new ArticleListVO();
            vo.setId(invocation.getArgument(0, Article.class).getId());
            return vo;
        });
        when(articleTagMapper.selectList(any())).thenReturn(List.of());
        when(userService.listByIds(any())).thenReturn(List.of());

        List<ArticleListVO> vos = articleService.assemble(List.of(published, offline, scheduled));

        assertEquals(List.of(1L, 2L, 3L), vos.stream().map(ArticleListVO::getId).toList());
        assertTrue(vos.get(0).getVisible(), "已发布到点应可见");
        assertFalse(vos.get(1).getVisible(), "已下架应不可见（收藏夹置灰依据）");
        assertFalse(vos.get(2).getVisible(), "定时中应不可见（收藏夹置灰依据）");
    }

    @Test
    void forceDelete_cascadesPhysicalDeletesAndClearsAutosave() {
        Article trashed = draftArticle(10L, 1L);
        when(articleMapper.selectAnyById(10L)).thenReturn(trashed);

        articleService.forceDelete(10L, jwtOf(1L));

        // article 自有：本行 + article_tag + 版本快照
        verify(articleMapper).physicalDeleteById(10L);
        verify(articleTagMapper).delete(any());
        verify(articleVersionMapper).physicalDeleteByArticleId(10L);
        // 跨模块级联归还各模块的 ArticleCascade 实现，文章域只编排遍历
        verify(commentCascade).purgeByArticleId(10L);
        verify(interactionCascade).purgeByArticleId(10L);
        verify(stringRedisTemplate).delete("blog:autosave:10:1");
    }

    @Test
    void forceDelete_adminOperatorClearsAuthorAndOperatorAutosave() {
        Article trashed = draftArticle(10L, 1L);
        when(articleMapper.selectAnyById(10L)).thenReturn(trashed);

        // ADMIN 代删他人文章：作者与操作者的自动保存草稿双键都要清
        articleService.forceDelete(10L, jwtOf(2L, "ROLE_" + UserRole.ADMIN.name()));

        verify(stringRedisTemplate).delete("blog:autosave:10:1");
        verify(stringRedisTemplate).delete("blog:autosave:10:2");
        verify(commentCascade).purgeByArticleId(10L);
        verify(interactionCascade).purgeByArticleId(10L);
    }

    @Test
    void forceDelete_adminOperator_notifiesAuthorWithTitle() {
        // 产品决策（违规强删通知作者）：admin 代删（operator ≠ author）→ 通知作者，参数为作者 id / 操作者 id / 被删标题
        Article trashed = draftArticle(10L, 1L);
        when(articleMapper.selectAnyById(10L)).thenReturn(trashed);

        articleService.forceDelete(10L, jwtOf(2L, "ROLE_" + UserRole.ADMIN.name()));

        verify(articleNotificationPort).notifyAuthorForceDeleted(1L, 2L, "旧标题");
    }

    @Test
    void forceDelete_authorSelf_noNotification() {
        // 作者自删：不通知（通知只针对代删场景）
        Article trashed = draftArticle(10L, 1L);
        when(articleMapper.selectAnyById(10L)).thenReturn(trashed);

        articleService.forceDelete(10L, jwtOf(1L));

        verify(articleNotificationPort, never()).notifyAuthorForceDeleted(any(), any(), any());
    }

    @Test
    void forceDelete_missingRowThrowsArticleNotExist() {
        when(articleMapper.selectAnyById(10L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.forceDelete(10L, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
    }

    // ---------- slug 固定链接（issue #14） ----------

    @Test
    void create_explicitSlugSavedAsIs() {
        when(articleMapper.selectIdBySlugAny("my-permalink")).thenReturn(null);
        doReturn(true).when(articleService).save(any(Article.class));
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setSlug("my-permalink");

        articleService.create(dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).save(captor.capture());
        assertEquals("my-permalink", captor.getValue().getSlug());
        // 显式 slug 一次落库，不走 id 兜底回写
        verify(articleService, never()).updateById(any(Article.class));
    }

    @Test
    void create_invalidSlugRejected() {
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setSlug("Hello_World");

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.create(dto, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_SLUG_INVALID.getCode(), e.getCode());
        verify(articleService, never()).save(any(Article.class));
    }

    @Test
    void create_conflictingSlugRejected() {
        // 含回收站占位：selectIdBySlugAny 查到 deleted=1 的行同样冲突
        when(articleMapper.selectIdBySlugAny("taken-slug")).thenReturn(99L);
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setSlug("taken-slug");

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.create(dto, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_SLUG_EXISTS.getCode(), e.getCode());
        verify(articleService, never()).save(any(Article.class));
    }

    @Test
    void create_generatesSlugFromTitle() {
        when(articleMapper.selectIdBySlugAny("hello-world-2024")).thenReturn(null);
        doReturn(true).when(articleService).save(any(Article.class));

        articleService.create(saveDto("Hello World 2024!", "正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).save(captor.capture());
        assertEquals("hello-world-2024", captor.getValue().getSlug());
    }

    @Test
    void create_generatedSlugConflictAppendsSuffix() {
        when(articleMapper.selectIdBySlugAny("hello-world")).thenReturn(7L);
        when(articleMapper.selectIdBySlugAny("hello-world-2")).thenReturn(null);
        doReturn(true).when(articleService).save(any(Article.class));

        articleService.create(saveDto("Hello World", "正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).save(captor.capture());
        assertEquals("hello-world-2", captor.getValue().getSlug());
    }

    @Test
    void create_pureCjkTitleFallsBackToPostId() {
        // 纯中文标题清洗不出 ASCII 字母数字：先插入取 id，同事务回写 post-{id}
        doAnswer(invocation -> {
            Article saved = invocation.getArgument(0);
            saved.setId(42L);
            return true;
        }).when(articleService).save(any(Article.class));
        doReturn(true).when(articleService).updateById(any(Article.class));

        articleService.create(saveDto("纯中文标题", "正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("post-42", captor.getValue().getSlug());
    }

    @Test
    void update_blankSlugKeepsExisting() {
        Article old = draftArticle(10L, 1L);
        old.setSlug("existing-slug");
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));

        // slug 缺省（null）= 保持现状，不重新生成
        articleService.update(10L, saveDto("新标题", "新正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("existing-slug", captor.getValue().getSlug());
    }

    @Test
    void update_sameSlugAsSelfAllowed() {
        Article old = draftArticle(10L, 1L);
        old.setSlug("my-slug");
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        ArticleSaveDTO dto = saveDto("新标题", "新正文", "DRAFT");
        dto.setSlug("my-slug");

        articleService.update(10L, dto, jwtOf(1L));

        // 与现值相同直接短路，不触发唯一性查询
        verify(articleMapper, never()).selectIdBySlugAny(anyString());
    }

    @Test
    void update_newSlugChecksUniquenessExcludingSelf() {
        Article old = draftArticle(10L, 1L);
        old.setSlug("old-slug");
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        // 独占：查到的持有者就是自己（防御性场景），放行
        when(articleMapper.selectIdBySlugAny("new-slug")).thenReturn(10L);
        ArticleSaveDTO dto = saveDto("新标题", "新正文", "DRAFT");
        dto.setSlug("new-slug");

        articleService.update(10L, dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("new-slug", captor.getValue().getSlug());
    }

    @Test
    void update_conflictingSlugRejected() {
        Article old = draftArticle(10L, 1L);
        old.setSlug("old-slug");
        doReturn(old).when(articleService).getById(10L);
        when(articleMapper.selectIdBySlugAny("other-slug")).thenReturn(8L);
        ArticleSaveDTO dto = saveDto("新标题", "新正文", "DRAFT");
        dto.setSlug("other-slug");

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.update(10L, dto, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_SLUG_EXISTS.getCode(), e.getCode());
    }

    @Test
    void update_invalidSlugRejected() {
        Article old = draftArticle(10L, 1L);
        old.setSlug("old-slug");
        doReturn(old).when(articleService).getById(10L);
        ArticleSaveDTO dto = saveDto("新标题", "新正文", "DRAFT");
        dto.setSlug("Bad Slug");

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.update(10L, dto, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_SLUG_INVALID.getCode(), e.getCode());
    }

    @Test
    void getDetailBySlug_visibleArticleReturnsAndIncrementsView() {
        Article published = draftArticle(10L, 1L);
        published.setSlug("hello-slug");
        published.setStatus(ArticleStatus.PUBLISHED.name());
        published.setPublishTime(LocalDateTime.now().minusDays(1));
        published.setViewCount(5L);
        doReturn(published).when(articleService).getOne(any(Wrapper.class));
        when(converter.convert(published, ArticleDetailVO.class)).thenReturn(new ArticleDetailVO());
        when(articleTagMapper.selectList(any())).thenReturn(List.of());
        when(userService.listByIds(any())).thenReturn(List.of());
        when(articleInteractionPort.statsOf(10L, null)).thenReturn(new ArticleInteractionStats(0, 0, false, false));
        when(articleCommentStatsPort.countVisibleByArticleId(10L)).thenReturn(0L);

        ArticleDetailVO vo = articleService.getDetailBySlug("hello-slug", null, null);

        assertEquals(6L, vo.getViewCount(), "可见详情浏览量内存 +1 与库内自增对齐");
        // 浏览量原子自增走 baseMapper.update
        verify(articleMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void getDetailBySlug_invisibleToAnonymousThrowsNotExist() {
        Article draft = draftArticle(10L, 1L);
        draft.setSlug("draft-slug");
        doReturn(draft).when(articleService).getOne(any(Wrapper.class));

        // 草稿对匿名不暴露存在性，统一报文章不存在；浏览量不自增
        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.getDetailBySlug("draft-slug", null, null));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void getDetailBySlug_unknownSlugThrowsNotExist() {
        doReturn(null).when(articleService).getOne(any(Wrapper.class));

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.getDetailBySlug("no-such-slug", null, null));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void getDetailBySlug_blankSlugThrowsNotExist() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.getDetailBySlug("  ", null, null));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
    }

    // ---------- 密码保护 / 私密文章（issue #15） ----------

    /**
     * 已到点的口令保护文章 fixture
     */
    private static Article passwordArticle(long id, long authorId, String hash) {
        Article article = draftArticle(id, authorId);
        article.setSlug("pwd-" + id);
        article.setStatus(ArticleStatus.PUBLISHED.name());
        article.setPublishTime(LocalDateTime.now().minusMinutes(1));
        article.setVisibility("PASSWORD");
        article.setAccessPasswordHash(hash);
        return article;
    }

    /**
     * 详情装配的公共桩（互动 / 评论计数 / 关联查询）
     */
    private void stubDetailAssembly(long articleId) {
        when(articleTagMapper.selectList(any())).thenReturn(List.of());
        when(userService.listByIds(any())).thenReturn(List.of());
        when(articleInteractionPort.statsOf(eq(articleId), any()))
                .thenReturn(new ArticleInteractionStats(0, 0, false, false));
        when(articleCommentStatsPort.countVisibleByArticleId(articleId)).thenReturn(0L);
    }

    @Test
    void create_passwordVisibilityStoresBcryptHashOnly() {
        when(articleMapper.selectIdBySlugAny(anyString())).thenReturn(null);
        doReturn(true).when(articleService).save(any(Article.class));
        when(passwordEncoder.encode("s3cret")).thenReturn("$2a$hash");
        ArticleSaveDTO dto = saveDto("Protected Post", "正文", "PUBLISHED");
        dto.setVisibility("PASSWORD");
        dto.setAccessPassword("s3cret");

        articleService.create(dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).save(captor.capture());
        Article saved = captor.getValue();
        assertEquals("PASSWORD", saved.getVisibility());
        // 口令只落哈希，明文不出现
        assertEquals("$2a$hash", saved.getAccessPasswordHash());
        verify(passwordEncoder).encode("s3cret");
    }

    @Test
    void create_passwordVisibilityWithoutPasswordRejected() {
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setVisibility("PASSWORD");

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.create(dto, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_PASSWORD_REQUIRED.getCode(), e.getCode());
        verify(articleService, never()).save(any(Article.class));
    }

    @Test
    void create_invalidVisibilityRejected() {
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setVisibility("HIDDEN");

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.create(dto, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_VISIBILITY_INVALID.getCode(), e.getCode());
        verify(articleService, never()).save(any(Article.class));
    }

    @Test
    void create_defaultVisibilityIsPublic() {
        when(articleMapper.selectIdBySlugAny(anyString())).thenReturn(null);
        doReturn(true).when(articleService).save(any(Article.class));

        articleService.create(saveDto("Plain Post", "正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).save(captor.capture());
        assertEquals("PUBLIC", captor.getValue().getVisibility());
        assertNull(captor.getValue().getAccessPasswordHash());
    }

    @Test
    void create_tooShortAccessPasswordRejected() {
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setVisibility("PASSWORD");
        dto.setAccessPassword("abc");

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.create(dto, jwtOf(1L)));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), e.getCode());
        verify(articleService, never()).save(any(Article.class));
    }

    @Test
    void update_switchToPasswordWithPasswordHashes() {
        Article old = draftArticle(10L, 1L);
        old.setVisibility("PUBLIC");
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        when(passwordEncoder.encode("newpass1")).thenReturn("$2a$new");
        ArticleSaveDTO dto = saveDto("新标题", "新正文", "DRAFT");
        dto.setVisibility("PASSWORD");
        dto.setAccessPassword("newpass1");

        articleService.update(10L, dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("PASSWORD", captor.getValue().getVisibility());
        assertEquals("$2a$new", captor.getValue().getAccessPasswordHash());
    }

    @Test
    void update_stayPasswordBlankPasswordKeepsHash() {
        Article old = passwordArticle(10L, 1L, "$2a$old");
        old.setStatus(ArticleStatus.DRAFT.name());
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        ArticleSaveDTO dto = saveDto("新标题", "新正文", "DRAFT");
        dto.setVisibility("PASSWORD");

        articleService.update(10L, dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("PASSWORD", captor.getValue().getVisibility());
        // 口令留空 = 保持既有哈希，不重新编码
        assertEquals("$2a$old", captor.getValue().getAccessPasswordHash());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void update_switchBackToPublicClearsHash() {
        Article old = passwordArticle(10L, 1L, "$2a$old");
        old.setStatus(ArticleStatus.DRAFT.name());
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        ArticleSaveDTO dto = saveDto("新标题", "新正文", "DRAFT");
        dto.setVisibility("PUBLIC");

        articleService.update(10L, dto, jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("PUBLIC", captor.getValue().getVisibility());
        // 切出 PASSWORD 清空残留哈希；MP updateById 写不进 null，另有显式 set null 的更新
        assertNull(captor.getValue().getAccessPasswordHash());
        verify(articleMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void update_blankVisibilityKeepsExisting() {
        Article old = passwordArticle(10L, 1L, "$2a$old");
        old.setStatus(ArticleStatus.DRAFT.name());
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));

        articleService.update(10L, saveDto("新标题", "新正文", "DRAFT"), jwtOf(1L));

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleService).updateById(captor.capture());
        assertEquals("PASSWORD", captor.getValue().getVisibility());
        assertEquals("$2a$old", captor.getValue().getAccessPasswordHash());
    }

    @Test
    void getDetail_passwordArticleWithoutTokenReturnsGateView() {
        Article article = passwordArticle(10L, 1L, "$2a$hash");
        doReturn(article).when(articleService).getById(10L);

        ArticleDetailVO vo = articleService.getDetail(10L, null, null);

        // 口令门：只证实「存在且需口令」，标题 / 正文不下发
        assertTrue(vo.getPasswordRequired());
        assertEquals(10L, vo.getId());
        assertEquals("pwd-10", vo.getSlug());
        assertEquals("PASSWORD", vo.getVisibility());
        assertNull(vo.getTitle());
        assertNull(vo.getContent());
        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void getDetail_passwordArticleWithValidTokenReturnsFullView() {
        Article article = passwordArticle(10L, 1L, "$2a$hash");
        article.setViewCount(3L);
        doReturn(article).when(articleService).getById(10L);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blog:article-access:10:tok-1")).thenReturn("$2a$hash");
        when(converter.convert(article, ArticleDetailVO.class)).thenReturn(new ArticleDetailVO());
        stubDetailAssembly(10L);

        ArticleDetailVO vo = articleService.getDetail(10L, null, "tok-1");

        assertFalse(vo.getPasswordRequired(), "令牌有效应放行完整详情");
        assertEquals(4L, vo.getViewCount(), "口令解锁访问视同可见阅读，浏览量内存 +1");
        verify(articleMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void getDetail_passwordArticleAuthorBypassesGate() {
        Article article = passwordArticle(10L, 1L, "$2a$hash");
        doReturn(article).when(articleService).getById(10L);
        when(converter.convert(article, ArticleDetailVO.class)).thenReturn(new ArticleDetailVO());
        stubDetailAssembly(10L);

        ArticleDetailVO vo = articleService.getDetail(10L, jwtOf(1L), null);

        assertFalse(vo.getPasswordRequired(), "作者本人不受口令门限制");
    }

    @Test
    void getDetail_scheduledPasswordArticleHidesExistence() {
        // 定时中的 PASSWORD 文章：未到点不出口令框，与不存在同语义
        Article article = passwordArticle(10L, 1L, "$2a$hash");
        article.setPublishTime(LocalDateTime.now().plusHours(1));
        doReturn(article).when(articleService).getById(10L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.getDetail(10L, null, null));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void getDetail_privateArticleAnonymousThrowsNotExist() {
        Article article = passwordArticle(10L, 1L, null);
        article.setVisibility("PRIVATE");
        doReturn(article).when(articleService).getById(10L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.getDetail(10L, null, null));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void getDetail_privateArticleAdminAllowed() {
        Article article = passwordArticle(10L, 1L, null);
        article.setVisibility("PRIVATE");
        doReturn(article).when(articleService).getById(10L);
        when(converter.convert(article, ArticleDetailVO.class)).thenReturn(new ArticleDetailVO());
        stubDetailAssembly(10L);

        ArticleDetailVO vo = articleService.getDetail(10L, jwtOf(2L, "ROLE_" + UserRole.ADMIN.name()), null);

        assertFalse(vo.getPasswordRequired(), "ADMIN 可看私密文章");
    }

    @Test
    void verifyAccessPassword_correctPasswordIssuesToken() {
        Article article = passwordArticle(10L, 1L, "$2a$hash");
        doReturn(article).when(articleService).getById(10L);
        when(passwordEncoder.matches("s3cret", "$2a$hash")).thenReturn(true);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(ops);

        var access = articleService.verifyAccessPassword(10L, "s3cret");

        assertEquals(7200L, access.getExpiresIn());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(ops).set(keyCaptor.capture(), eq("$2a$hash"), eq(Duration.ofHours(2)));
        // 令牌键格式 blog:article-access:{articleId}:{uuid}，与签发的 token 一致
        assertEquals("blog:article-access:10:" + access.getAccessToken(), keyCaptor.getValue());
    }

    @Test
    void getDetail_passwordChangedRejectsOldGrant() {
        Article article = passwordArticle(10L, 1L, "$2a$new");
        doReturn(article).when(articleService).getById(10L);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blog:article-access:10:old-token")).thenReturn("$2a$old");
        assertTrue(articleService.getDetail(10L, null, "old-token").getPasswordRequired());
        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void create_multibytePasswordBeyondBcryptLimitRejected() {
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setVisibility("PASSWORD");
        dto.setAccessPassword("密".repeat(25));
        BusinessException e = assertThrows(BusinessException.class, () -> articleService.create(dto, jwtOf(1L)));
        assertEquals(StatusCode.PARAM_INVALID.getCode(), e.getCode());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void update_passwordOnlyRotatesHashWithoutChangingVisibility() {
        Article old = passwordArticle(10L, 1L, "$2a$old");
        doReturn(old).when(articleService).getById(10L);
        doReturn(true).when(articleService).updateById(any(Article.class));
        when(passwordEncoder.encode("newpass1")).thenReturn("$2a$new");
        ArticleSaveDTO dto = saveDto("标题", "正文", "DRAFT");
        dto.setAccessPassword("newpass1");
        articleService.update(10L, dto, jwtOf(1L));
        assertEquals("PASSWORD", old.getVisibility());
        assertEquals("$2a$new", old.getAccessPasswordHash());
    }

    @Test
    void verifyAccessPassword_wrongPasswordRejected() {
        Article article = passwordArticle(10L, 1L, "$2a$hash");
        doReturn(article).when(articleService).getById(10L);
        when(passwordEncoder.matches("bad", "$2a$hash")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.verifyAccessPassword(10L, "bad"));
        assertEquals(StatusCode.ARTICLE_PASSWORD_INVALID.getCode(), e.getCode());
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void verifyAccessPassword_nonPasswordArticleThrowsNotExist() {
        // PUBLIC / PRIVATE / 草稿 / 下架 一律与不存在同语义，不暴露存在性
        Article publicArticle = draftArticle(10L, 1L);
        publicArticle.setVisibility("PUBLIC");
        doReturn(publicArticle).when(articleService).getById(10L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.verifyAccessPassword(10L, "any"));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void verifyAccessPassword_missingArticleThrowsNotExist() {
        doReturn(null).when(articleService).getById(404L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.verifyAccessPassword(404L, "any"));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
    }
}

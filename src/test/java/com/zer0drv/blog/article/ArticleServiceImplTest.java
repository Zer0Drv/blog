package com.zer0drv.blog.article;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.domain.ArticleVersion;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.enums.ArticleStatus;
import com.zer0drv.blog.article.enums.EditorType;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.article.mapper.ArticleVersionMapper;
import com.zer0drv.blog.article.service.impl.ArticleServiceImpl;
import com.zer0drv.blog.category.mapper.CategoryMapper;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.interaction.domain.ArticleFavorite;
import com.zer0drv.blog.interaction.domain.ArticleLike;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.ArticleFavoriteMapper;
import com.zer0drv.blog.interaction.mapper.ArticleLikeMapper;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private CommentMapper commentMapper;
    @Mock
    private CommentLikeMapper commentLikeMapper;
    @Mock
    private ArticleLikeMapper articleLikeMapper;
    @Mock
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Mock
    private ArticleVersionMapper articleVersionMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;

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
        TableInfoHelper.initTableInfo(assistant, Comment.class);
        TableInfoHelper.initTableInfo(assistant, CommentLike.class);
        TableInfoHelper.initTableInfo(assistant, ArticleLike.class);
        TableInfoHelper.initTableInfo(assistant, ArticleFavorite.class);
    }

    @BeforeEach
    void setUp() {
        // 注意构造参数顺序与 @RequiredArgsConstructor 字段声明一致（baseMapper 由 MP 注入，此处反射补）
        articleService = spy(new ArticleServiceImpl(
                articleTagMapper, tagMapper, categoryMapper, userService, converter,
                commentMapper, commentLikeMapper, articleLikeMapper, articleFavoriteMapper,
                articleVersionMapper, stringRedisTemplate, objectMapper));
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
                .thenReturn(target, null);
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
    void forceDelete_cascadesPhysicalDeletesAndClearsAutosave() {
        Article trashed = draftArticle(10L, 1L);
        when(articleMapper.selectAnyById(10L)).thenReturn(trashed);
        when(articleMapper.selectAllCommentIdsByArticleId(10L)).thenReturn(List.of(100L, 101L));

        articleService.forceDelete(10L, jwtOf(1L));

        verify(articleMapper).physicalDeleteById(10L);
        verify(articleMapper).physicalDeleteCommentsByArticleId(10L);
        verify(articleVersionMapper).physicalDeleteByArticleId(10L);
        verify(stringRedisTemplate).delete("blog:autosave:10:1");
    }

    @Test
    void forceDelete_missingRowThrowsArticleNotExist() {
        when(articleMapper.selectAnyById(10L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> articleService.forceDelete(10L, jwtOf(1L)));
        assertEquals(StatusCode.ARTICLE_NOT_EXIST.getCode(), e.getCode());
    }
}

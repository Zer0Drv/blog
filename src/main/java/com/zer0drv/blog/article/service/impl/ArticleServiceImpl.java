package com.zer0drv.blog.article.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.article.api.ArticleCascade;
import com.zer0drv.blog.article.api.ArticleCommentStatsPort;
import com.zer0drv.blog.article.api.ArticleInteractionPort;
import com.zer0drv.blog.article.api.ArticleInteractionStats;
import com.zer0drv.blog.article.api.ArticleNotificationPort;
import com.zer0drv.blog.article.api.ArticleStatus;
import com.zer0drv.blog.article.api.Visibility;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleSlugger;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.domain.ArticleVersion;
import com.zer0drv.blog.article.domain.ArticleVisibility;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.enums.EditorType;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.article.mapper.ArticleVersionMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArchiveMonthVO;
import com.zer0drv.blog.article.vo.ArticleAccessVO;
import com.zer0drv.blog.article.vo.ArticleAuthorVO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.article.vo.ArticleVersionDetailVO;
import com.zer0drv.blog.article.vo.ArticleVersionVO;
import com.zer0drv.blog.category.domain.Category;
import com.zer0drv.blog.category.mapper.CategoryMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.tag.domain.Tag;
import com.zer0drv.blog.tag.mapper.TagMapper;
import com.zer0drv.blog.tag.vo.TagVO;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import io.github.linpeilie.Converter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Yoruhaki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    /**
     * 摘要缺省长度：取 content 纯文本前 200 字
     */
    private static final int SUMMARY_MAX_LENGTH = 200;

    /**
     * 定时发布最小提前量（秒）：publishTime 必须晚于当前时间至少这么久
     */
    private static final long SCHEDULE_MIN_LEAD_SECONDS = 60;

    /**
     * 自动保存草稿 Redis 键：blog:autosave:{articleId}:{userId}，TTL 2 小时
     */
    private static final String AUTOSAVE_KEY = "blog:autosave:%d:%d";
    private static final Duration AUTOSAVE_TTL = Duration.ofHours(2);

    /**
     * 归档月份格式：yyyy-MM
     */
    private static final DateTimeFormatter ARCHIVE_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 回收站伪状态（非 ArticleStatus 枚举值，service 层先行拦截）
     */
    private static final String PSEUDO_STATUS_TRASH = "TRASH";

    /**
     * 自动生成 slug 的冲突追加序号上限（-2 … -9999）；撞满属理论极端，
     * 并发/超界冲突最终由 uk_article_slug 唯一索引兜底
     */
    private static final int SLUG_SUFFIX_MAX = 9999;

    /**
     * 口令访问令牌 Redis 键（issue #15）：blog:article-access:{articleId}:{token}，
     * 值为签发时口令哈希，TTL 2 小时；改口令后旧令牌失效。口令校验只在校验端点（限流守护）发生，
     * 详情接口只查令牌存在性，令牌本体是 122 位随机 UUID，无爆破面
     */
    private static final String ACCESS_KEY = "blog:article-access:%d:%s";
    private static final Duration ACCESS_TTL = Duration.ofHours(2);

    /**
     * 文章访问口令长度边界（4~64）：独立于账号密码策略（8~64 且字母+数字），
     * 口令用于口头分享，强度由作者自担；后端 BCrypt 哈希 + 校验端点限流兜底爆破面
     */
    private static final int ACCESS_PASSWORD_MIN_LENGTH = 4;
    private static final int ACCESS_PASSWORD_MAX_LENGTH = 64;

    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final CategoryMapper categoryMapper;
    private final UserService userService;
    private final Converter converter;
    private final ArticleInteractionPort articleInteractionPort;
    private final ArticleCommentStatsPort articleCommentStatsPort;
    private final ArticleVersionMapper articleVersionMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final List<ArticleCascade> articleCascades;
    private final ArticleNotificationPort articleNotificationPort;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * 全文搜索开关（P0）：true 走 ngram FULLTEXT（仅 MySQL）；false 走 LIKE 兜底（H2 测试环境）
     */
    @Value("${blog.search.fulltext-enabled:true}")
    private boolean fulltextEnabled;

    /**
     * 级联清场端口兜底（issue #24 第三步）：comment / interaction 两个模块必须各自
     * 注册 ArticleCascade 实现，缺失说明有模块忘了接级联——启动即快速失败，
     * 避免 forceDelete 静默漏删外模块数据。
     */
    @PostConstruct
    void assertArticleCascadesPresent() {
        if (articleCascades.size() < 2) {
            throw new IllegalStateException(
                    "ArticleCascade 实现不足 2 个（当前 " + articleCascades.size() + " 个），"
                            + "comment / interaction 模块必须各自实现文章级联清场");
        }
        log.info("ArticleCascade 级联清场实现已装配：{}", articleCascades.stream()
                .map(cascade -> cascade.getClass().getSimpleName()).toList());
    }

    @Override
    public PageResult<ArticleListVO> pagePublished(long page, long size, String keyword, Long tagId, Long categoryId) {
        LambdaQueryWrapper<Article> wrapper = ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class))
                .eq(Objects.nonNull(categoryId), Article::getCategoryId, categoryId)
                // M5：置顶优先，其后按发布时间倒序
                .orderByDesc(Article::getIsTop)
                .orderByDesc(Article::getPublishTime);
        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            // keyword 模糊匹配 title / summary
            wrapper.and(w -> w.like(Article::getTitle, keyword).or().like(Article::getSummary, keyword));
        }
        if (Objects.nonNull(tagId)) {
            List<Long> articleIds = articleTagMapper.selectList(Wrappers.lambdaQuery(ArticleTag.class)
                            .eq(ArticleTag::getTagId, tagId))
                    .stream().map(ArticleTag::getArticleId).toList();
            if (articleIds.isEmpty()) {
                return PageResult.of(List.of(), 0, page, size);
            }
            wrapper.in(Article::getId, articleIds);
        }
        Page<Article> result = page(new Page<>(page, size), wrapper);
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public ArticleDetailVO getDetail(Long id, Jwt jwt, String accessToken) {
        Article article = getById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        return detailView(article, jwt, accessToken);
    }

    @Override
    public ArticleDetailVO getDetailBySlug(String slug, Jwt jwt, String accessToken) {
        if (Objects.isNull(slug) || slug.isBlank()) {
            // 空 slug 不构成有效固定链接，统一按不存在处理
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        // MP 逻辑删除自动拼 deleted=0：回收站文章的 slug 对详情不可见（但仍占唯一坑位）
        Article article = getOne(Wrappers.lambdaQuery(Article.class).eq(Article::getSlug, slug.trim()));
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        return detailView(article, jwt, accessToken);
    }

    /**
     * 详情视图装配（id / slug 两入口共用）：可见性判定、浏览量原子自增、关联与互动填充。
     * 非可见状态仅作者本人 / ADMIN 可见；对匿名与非作者不暴露文章存在性，统一报不存在。
     * 口令保护（issue #15）：已到点的 PASSWORD 文章对公众先出「需口令」极简视图
     * （不下发标题 / 正文），持有效访问令牌则放行并按可见文章计浏览量
     */
    private ArticleDetailVO detailView(Article article, Jwt jwt, String accessToken) {
        boolean visible = ArticleVisibility.isVisible(article);
        boolean passwordUnlocked = false;
        if (!visible && !isAuthorOrAdmin(article, jwt)) {
            if (isPasswordGated(article)) {
                if (hasAccessGrant(article, accessToken)) {
                    passwordUnlocked = true;
                } else {
                    // 只证实「存在且需口令」，标题 / 摘要 / 正文等一律不下发
                    return passwordRequiredView(article);
                }
            } else {
                throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
            }
        }
        // 浏览量原子自增（一条 update 语句，不先查后写）；草稿 / 下架 / 定时中详情不加；
        // 口令解锁的访问视同可见访问（真实公众阅读）
        if (visible || passwordUnlocked) {
            baseMapper.update(null, Wrappers.lambdaUpdate(Article.class)
                    .setSql("view_count = view_count + 1")
                    .eq(Article::getId, article.getId()));
        }
        ArticleDetailVO vo = converter.convert(article, ArticleDetailVO.class);
        vo.setPasswordRequired(false);
        fillAssociations(List.of(vo), List.of(article));
        fillInteraction(vo, article, visible || passwordUnlocked, jwt);
        return vo;
    }

    /**
     * 口令门禁判定（issue #15）：已到点的 PASSWORD 文章。未到点 / 草稿 / 下架的
     * PASSWORD 文章不出门禁（对公众与不存在同语义，避免提前暴露存在性）
     */
    private boolean isPasswordGated(Article article) {
        return Visibility.PASSWORD.name().equals(article.getVisibility())
                && ArticleVisibility.isPublishedDue(article);
    }

    /**
     * 访问令牌校验：Redis 中签发时的哈希须与当前口令哈希一致（TTL 2h）
     */
    private boolean hasAccessGrant(Article article, String accessToken) {
        if (Objects.isNull(accessToken) || accessToken.isBlank()) {
            return false;
        }
        String grantedHash = stringRedisTemplate.opsForValue()
                .get(accessKey(article.getId(), accessToken.trim()));
        // 绑定签发时的口令哈希：改口令或切换可见性后，旧令牌立即失效。
        return Objects.nonNull(grantedHash) && grantedHash.equals(article.getAccessPasswordHash());
    }

    /**
     * 「需口令」极简视图：仅回填 id / slug / visibility / passwordRequired，
     * 标题 / 摘要 / 正文 / 作者等均不下发（口令页只需知道「往哪篇文章提交口令」）
     */
    private ArticleDetailVO passwordRequiredView(Article article) {
        ArticleDetailVO vo = new ArticleDetailVO();
        vo.setId(article.getId());
        vo.setSlug(article.getSlug());
        vo.setVisibility(article.getVisibility());
        vo.setPasswordRequired(true);
        return vo;
    }

    /**
     * 校验口令并签发访问令牌（issue #15）。非「已到点的 PASSWORD 文章」一律按不存在
     * （含 PRIVATE / 草稿 / 下架 / 回收站），不暴露存在性。限流编排在控制器层
     * （CaptchaGuard 登录同款双维度：IP + 文章 id），本方法只做口令比对与签发
     */
    @Override
    public ArticleAccessVO verifyAccessPassword(Long id, String password) {
        Article article = getById(id);
        if (Objects.isNull(article) || !isPasswordGated(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        String hash = Objects.nonNull(article.getAccessPasswordHash()) ? article.getAccessPasswordHash() : "";
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(StatusCode.ARTICLE_PASSWORD_INVALID);
        }
        if (!passwordEncoder.matches(password, hash)) {
            throw new BusinessException(StatusCode.ARTICLE_PASSWORD_INVALID);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(accessKey(id, token), hash, ACCESS_TTL);
        return new ArticleAccessVO(token, ACCESS_TTL.toSeconds());
    }

    private static String accessKey(Long articleId, String token) {
        return ACCESS_KEY.formatted(articleId, token);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ArticleSaveDTO dto, Jwt jwt) {
        validateSaveDTO(dto);
        Article article = new Article();
        article.setTitle(dto.getTitle());
        // 存储型 XSS 防线（blog-ui#9 后端部分）：RICHTEXT 内容入库前白名单清洗
        String content = sanitizeContent(dto.getContent(), dto.getEditorType());
        article.setContent(content);
        article.setEditorType(dto.getEditorType());
        article.setSummary(resolveSummary(dto));
        article.setCover(Objects.isNull(dto.getCover()) ? "" : dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        article.setAuthorId(JwtSubjects.userIdOf(jwt));
        article.setContentText(toPlainText(content));
        String status = resolveSaveStatus(dto.getStatus());
        article.setStatus(status);
        // 可见性（issue #15）：缺省 PUBLIC；PASSWORD 必须带口令（只落 BCrypt 哈希）
        article.setVisibility(resolveVisibility(dto.getVisibility()));
        article.setAccessPasswordHash(resolvePasswordHash(dto, null));
        // slug 固定链接（issue #14）：显式指定校验格式与唯一性；缺省按标题清洗生成，
        // 清洗不出 ASCII 字母数字（如纯中文标题）时暂留 null，插入取 id 后回写 post-{id}
        article.setSlug(resolveSlugForCreate(dto));
        // 新建直接发布：写首次发布时间（可定时；status=DRAFT 时忽略 publishTime）
        if (ArticleStatus.PUBLISHED.name().equals(status)) {
            article.setPublishTime(resolvePublishTimeOnPublish(dto.getPublishTime()));
        }
        save(article);
        if (Objects.isNull(article.getSlug())) {
            // id 兜底回写（同事务）：post-{id} 天然唯一，无需再查重
            article.setSlug("post-" + article.getId());
            updateById(article);
        }
        replaceTagRelations(article.getId(), dto.getTagIds());
        queuePublication(article);
        return article.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArticleSaveDTO dto, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        validateSaveDTO(dto);
        // P0 版本历史：先把更新前的整行快照为新版本（同事务；create/updateStatus 不产快照）
        snapshotVersion(article);
        article.setTitle(dto.getTitle());
        // 存储型 XSS 防线（blog-ui#9 后端部分）：RICHTEXT 内容入库前白名单清洗
        String content = sanitizeContent(dto.getContent(), dto.getEditorType());
        article.setContent(content);
        article.setContentText(toPlainText(content));
        article.setEditorType(dto.getEditorType());
        article.setSummary(resolveSummary(dto));
        article.setCover(Objects.isNull(dto.getCover()) ? "" : dto.getCover());
        article.setCategoryId(dto.getCategoryId());
        // slug 固定链接（issue #14）：显式指定且与现值不同才校验唯一性并更新；
        // 留空 = 保持现状（不重新生成，已分享出去的固定链接不因编辑正文而漂移）
        if (Objects.nonNull(dto.getSlug()) && !dto.getSlug().isBlank()) {
            String slug = dto.getSlug().trim();
            assertSlugFormat(slug);
            if (!slug.equals(article.getSlug())) {
                assertSlugAvailable(slug, id);
                article.setSlug(slug);
            }
        }
        // 可见性（issue #15）：留空 = 保持现状；PASSWORD 无既有口令时必须随本次设置新口令；
        // 切回 PUBLIC / PRIVATE 时清空口令哈希（口令不残留在库）
        boolean clearPasswordHash = false;
        if ((Objects.nonNull(dto.getVisibility()) && !dto.getVisibility().isBlank())
                || (Objects.nonNull(dto.getAccessPassword()) && !dto.getAccessPassword().isBlank())) {
            String visibility = Objects.isNull(dto.getVisibility()) || dto.getVisibility().isBlank()
                    ? article.getVisibility() : resolveVisibility(dto.getVisibility());
            dto.setVisibility(visibility);
            String passwordHash = resolvePasswordHash(dto, article);
            article.setVisibility(visibility);
            article.setAccessPasswordHash(passwordHash);
            // MP updateById 默认 NOT_NULL 策略写不进 null，清哈希需随后显式 set null
            clearPasswordHash = Objects.isNull(passwordHash);
        }
        if (Objects.nonNull(dto.getStatus()) && !dto.getStatus().isBlank()) {
            String status = resolveSaveStatus(dto.getStatus());
            article.setStatus(status);
            if (ArticleStatus.PUBLISHED.name().equals(status)) {
                if (Objects.isNull(article.getPublishTime())) {
                    // 首次发布：写发布时间（可定时）
                    article.setPublishTime(resolvePublishTimeOnPublish(dto.getPublishTime()));
                } else if (article.getPublishTime().isAfter(LocalDateTime.now())
                        && Objects.nonNull(dto.getPublishTime())) {
                    // 定时中（未上线）：允许改档期；已上线（publish_time <= now）的文章忽略 DTO 的 publishTime，不可改档期
                    article.setPublishTime(resolvePublishTimeOnPublish(dto.getPublishTime()));
                }
            }
        }
        updateById(article);
        if (clearPasswordHash) {
            baseMapper.update(null, Wrappers.lambdaUpdate(Article.class)
                    .set(Article::getAccessPasswordHash, null)
                    .eq(Article::getId, id));
        }
        replaceTagRelations(article.getId(), dto.getTagIds());
        queuePublication(article);
        // P0 自动保存：正式保存成功后删除该操作者的草稿
        deleteAutosave(id, JwtSubjects.userIdOf(jwt));
    }

    /**
     * 删除文章（P0 回收站）：只逻辑删 article 本行，不再级联清 article_tag / 评论 / 点赞收藏，
     * 保证回收站可完整恢复；级联物理删移到 forceDelete
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long id, Jwt jwt) {
        // MP 逻辑删除会自动拼 deleted=0，回收站行必须手写 SQL 查
        Article article = baseMapper.selectDeletedById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        assertAuthorOrAdmin(article, jwt);
        // 恢复回草稿态，不直接上线
        baseMapper.restoreDeleted(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceDelete(Long id, Jwt jwt) {
        // 彻底删除允许针对回收站中的行，存在性校验含已逻辑删除的行
        Article article = baseMapper.selectAnyById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        assertAuthorOrAdmin(article, jwt);
        // 物理删除本行 + article 自有级联（article_tag 无逻辑删除字段，物理删除；版本快照同理）
        baseMapper.physicalDeleteById(id);
        articleTagMapper.delete(Wrappers.lambdaQuery(ArticleTag.class).eq(ArticleTag::getArticleId, id));
        articleVersionMapper.physicalDeleteByArticleId(id);
        // 外模块级联清场：由各模块的 ArticleCascade 实现删自己拥有的表
        // （comment + comment_like、article_like + article_favorite），同步同事务，任一失败整体回滚
        articleCascades.forEach(cascade -> cascade.purgeByArticleId(id));
        // 清作者与操作者的自动保存草稿
        deleteAutosave(id, article.getAuthorId());
        Long operatorId = JwtSubjects.userIdOf(jwt);
        if (!operatorId.equals(article.getAuthorId())) {
            deleteAutosave(id, operatorId);
            // 产品决策（违规强删通知作者）：admin 代删时通知作者「文章已被删除」；作者自删不通知。
            // 放在事务内级联之后；通知失败不回滚删除——端口实现内部三通道（落库/WS/邮件）独立兜底
            articleNotificationPort.notifyAuthorForceDeleted(article.getAuthorId(), operatorId, article.getTitle());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, ArticleStatusDTO dto, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        if (!ArticleStatus.isValid(dto.getStatus())) {
            throw new BusinessException(StatusCode.ARTICLE_STATUS_INVALID);
        }
        article.setStatus(dto.getStatus());
        if (ArticleStatus.PUBLISHED.name().equals(dto.getStatus()) && Objects.isNull(article.getPublishTime())) {
            // 截断到秒，原因同 resolvePublishTimeOnPublish
            article.setPublishTime(LocalDateTime.now().withNano(0));
        }
        updateById(article);
        queuePublication(article);
    }

    private void queuePublication(Article article) {
        if (ArticleStatus.PUBLISHED.name().equals(article.getStatus())
                && Visibility.PUBLIC.name().equals(article.getVisibility()) && article.getPublishTime() != null) {
            eventPublisher.publishEvent(new com.zer0drv.blog.article.api.ArticlePublicationCandidate(
                    article.getId(), article.getPublishTime()));
        }
    }

    @Override
    public PageResult<ArticleListVO> pageMine(long page, long size, String status, Jwt jwt) {
        Long userId = JwtSubjects.userIdOf(jwt);
        // P0 回收站伪状态：先拦截，MP 逻辑删除会自动拼 deleted=0，必须手写 SQL 绕过
        if (PSEUDO_STATUS_TRASH.equals(status)) {
            Page<Article> trash = baseMapper.selectTrashPage(new Page<>(page, size), userId);
            return PageResult.of(assemble(trash.getRecords()), trash.getTotal(), page, size);
        }
        LambdaQueryWrapper<Article> wrapper = Wrappers.lambdaQuery(Article.class)
                .eq(Article::getAuthorId, userId)
                .orderByDesc(Article::getUpdateTime);
        if (Objects.nonNull(status) && !status.isBlank()) {
            if (!ArticleStatus.isValid(status)) {
                throw new BusinessException(StatusCode.ARTICLE_STATUS_INVALID);
            }
            wrapper.eq(Article::getStatus, status);
        }
        Page<Article> result = page(new Page<>(page, size), wrapper);
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public List<ArticleVersionVO> listVersions(Long id, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        return articleVersionMapper.selectList(Wrappers.lambdaQuery(ArticleVersion.class)
                        .eq(ArticleVersion::getArticleId, id)
                        .orderByDesc(ArticleVersion::getVersion))
                .stream().map(this::toVersionVO).toList();
    }

    @Override
    public ArticleVersionDetailVO getVersion(Long id, Integer version, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        return toVersionDetailVO(requireVersion(id, version));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreVersion(Long id, Integer version, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        ArticleVersion snapshot = requireVersion(id, version);
        // 恢复也留痕：覆盖前先对当前行产一个新快照
        snapshotVersion(article);
        article.setTitle(snapshot.getTitle());
        article.setSummary(snapshot.getSummary());
        // 存量快照可能产自 XSS 清洗上线前，恢复时同样过白名单（幂等，已清洗内容不受影响）
        String content = sanitizeContent(snapshot.getContent(), snapshot.getEditorType());
        article.setContent(content);
        article.setContentText(toPlainText(content));
        article.setEditorType(snapshot.getEditorType());
        article.setCover(snapshot.getCover());
        article.setCategoryId(snapshot.getCategoryId());
        // status / publishTime 不动（SPEC：恢复不回改发布状态与档期）
        updateById(article);
    }

    @Override
    public Map<String, Object> saveAutosave(Long id, AutosaveDTO dto, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        Long userId = JwtSubjects.userIdOf(jwt);
        long savedAt = System.currentTimeMillis();
        Map<String, Object> draft = new HashMap<>();
        draft.put("title", dto.getTitle());
        draft.put("content", dto.getContent());
        draft.put("summary", dto.getSummary());
        draft.put("cover", dto.getCover());
        draft.put("categoryId", dto.getCategoryId());
        if (Objects.nonNull(dto.getVisibility())) draft.put("visibility", resolveVisibility(dto.getVisibility()));
        draft.put("savedAt", savedAt);
        stringRedisTemplate.opsForValue().set(autosaveKey(id, userId),
                objectMapper.writeValueAsString(draft), AUTOSAVE_TTL);
        return Map.of("savedAt", savedAt);
    }

    @Override
    public Map<String, Object> getAutosave(Long id, Jwt jwt) {
        Article article = requireArticle(id);
        assertAuthorOrAdmin(article, jwt);
        Long userId = JwtSubjects.userIdOf(jwt);
        String json = stringRedisTemplate.opsForValue().get(autosaveKey(id, userId));
        if (Objects.isNull(json) || json.isBlank()) {
            return Map.of("exists", false);
        }
        try {
            Map<String, Object> draft = objectMapper.readValue(json, Map.class);
            draft.put("exists", true);
            return draft;
        } catch (RuntimeException e) {
            // 草稿 JSON 损坏时按不存在处理，不影响正式编辑
            return Map.of("exists", false);
        }
    }

    @Override
    public PageResult<ArticleListVO> search(String keyword, long page, long size) {
        if (Objects.isNull(keyword) || keyword.isBlank()) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "搜索关键字不能为空");
        }
        String kw = keyword.trim();
        Page<Article> result;
        if (fulltextEnabled) {
            // 生产（MySQL）：ngram FULLTEXT，MATCH...AGAINST 仅 MySQL 可跑
            result = baseMapper.searchByFulltext(new Page<>(page, size), kw);
        } else {
            // 兜底（H2 测试 / 未建全文索引）：title/summary/content_text 三路 LIKE
            result = page(new Page<>(page, size), ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class))
                    .and(w -> w.like(Article::getTitle, kw)
                            .or().like(Article::getSummary, kw)
                            .or().like(Article::getContentText, kw))
                    .orderByDesc(Article::getPublishTime));
        }
        return PageResult.of(assemble(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    public List<ArchiveMonthVO> archives() {
        // 只取四列，Java 内存按月分组（个人博客量级，不用 SQL 分组方言）
        List<Article> articles = list(ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class)
                        .select(Article::getId, Article::getTitle, Article::getSlug, Article::getPublishTime))
                .orderByDesc(Article::getPublishTime));
        // LinkedHashMap 保持首见顺序：publish_time 倒序遍历 → 月份天然倒序，月内文章亦倒序
        Map<String, List<ArchiveMonthVO.Item>> grouped = new LinkedHashMap<>();
        for (Article article : articles) {
            ArchiveMonthVO.Item item = new ArchiveMonthVO.Item();
            item.setId(article.getId());
            item.setTitle(article.getTitle());
            item.setSlug(article.getSlug());
            item.setPublishTime(article.getPublishTime());
            grouped.computeIfAbsent(article.getPublishTime().format(ARCHIVE_MONTH_FORMAT),
                    k -> new ArrayList<>()).add(item);
        }
        return grouped.entrySet().stream().map(entry -> {
            ArchiveMonthVO vo = new ArchiveMonthVO();
            vo.setMonth(entry.getKey());
            vo.setCount(entry.getValue().size());
            vo.setArticles(entry.getValue());
            return vo;
        }).toList();
    }

    /**
     * 文章版本快照：把当前行（更新前的旧行）存为「当前最大版本 + 1」。
     * 仅 update / restoreVersion 调用；create / updateStatus 不产快照；标签不纳入版本（SPEC 决策）。
     */
    private void snapshotVersion(Article article) {
        ArticleVersion latest = articleVersionMapper.selectOne(Wrappers.lambdaQuery(ArticleVersion.class)
                .eq(ArticleVersion::getArticleId, article.getId())
                .orderByDesc(ArticleVersion::getVersion)
                .last("LIMIT 1"));
        ArticleVersion snapshot = new ArticleVersion();
        snapshot.setArticleId(article.getId());
        snapshot.setVersion(Objects.isNull(latest) ? 1 : latest.getVersion() + 1);
        snapshot.setTitle(article.getTitle());
        snapshot.setSummary(Objects.isNull(article.getSummary()) ? "" : article.getSummary());
        snapshot.setContent(article.getContent());
        snapshot.setEditorType(article.getEditorType());
        snapshot.setCover(Objects.isNull(article.getCover()) ? "" : article.getCover());
        snapshot.setCategoryId(article.getCategoryId());
        articleVersionMapper.insert(snapshot);
    }

    private ArticleVersion requireVersion(Long articleId, Integer version) {
        ArticleVersion snapshot = articleVersionMapper.selectOne(Wrappers.lambdaQuery(ArticleVersion.class)
                .eq(ArticleVersion::getArticleId, articleId)
                .eq(ArticleVersion::getVersion, version));
        if (Objects.isNull(snapshot)) {
            throw new BusinessException(StatusCode.ARTICLE_VERSION_NOT_EXIST);
        }
        return snapshot;
    }

    /**
     * 发布时的发布时间：publishTime 为空写 now；非空即定时发布，
     * 必须晚于当前时间至少 60 秒，否则 PARAM_INVALID
     */
    private LocalDateTime resolvePublishTimeOnPublish(LocalDateTime publishTime) {
        if (Objects.isNull(publishTime)) {
            // 截断到秒：DATETIME 精度为秒且会四舍五入，带纳秒的 now() 可能进位到未来，
            // 导致「立即发布」的文章在 publish_time <= now 谓词下短暂不可见
            return LocalDateTime.now().withNano(0);
        }
        if (!publishTime.isAfter(LocalDateTime.now().plusSeconds(SCHEDULE_MIN_LEAD_SECONDS))) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "定时发布时间必须晚于当前时间");
        }
        return publishTime;
    }

    /**
     * 富文本 XSS 清洗（blog-ui#9 后端部分）：RICHTEXT 内容入库前用 jsoup Safelist.relaxed()
     * 白名单清洗（允许常见排版标签与 img 的 http/https src，剥离 script/on* 事件/危险协议）；
     * MARKDOWN 内容按原文存储——Markdown 源码不是 HTML，清洗会破坏合法语法，
     * 渲染侧由前端 DOMPurify 兜底（与本清洗构成纵深防御）。
     */
    private String sanitizeContent(String content, String editorType) {
        if (Objects.isNull(content) || content.isBlank()) {
            return content;
        }
        if (EditorType.RICHTEXT.name().equals(editorType)) {
            return Jsoup.clean(content, Safelist.relaxed());
        }
        return content;
    }

    /**
     * 正文清洗为纯文本（全文搜索 content_text 用）：去 HTML 标签 → 去 Markdown 控制符 → 压缩空白
     */
    private String toPlainText(String content) {
        if (Objects.isNull(content)) {
            return "";
        }
        return content
                .replaceAll("<[^>]+>", " ")
                .replaceAll("[#*`>\\-+=|\\[\\]()!~_.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String autosaveKey(Long articleId, Long userId) {
        return AUTOSAVE_KEY.formatted(articleId, userId);
    }

    private void deleteAutosave(Long articleId, Long userId) {
        stringRedisTemplate.delete(autosaveKey(articleId, userId));
    }

    private ArticleVersionVO toVersionVO(ArticleVersion snapshot) {
        ArticleVersionVO vo = new ArticleVersionVO();
        vo.setId(snapshot.getId());
        vo.setVersion(snapshot.getVersion());
        vo.setTitle(snapshot.getTitle());
        vo.setCreateTime(snapshot.getCreateTime());
        return vo;
    }

    private ArticleVersionDetailVO toVersionDetailVO(ArticleVersion snapshot) {
        ArticleVersionDetailVO vo = new ArticleVersionDetailVO();
        vo.setId(snapshot.getId());
        vo.setVersion(snapshot.getVersion());
        vo.setTitle(snapshot.getTitle());
        vo.setCreateTime(snapshot.getCreateTime());
        vo.setSummary(snapshot.getSummary());
        vo.setContent(snapshot.getContent());
        vo.setEditorType(snapshot.getEditorType());
        vo.setCover(snapshot.getCover());
        vo.setCategoryId(snapshot.getCategoryId());
        return vo;
    }

    /**
     * 校验保存入参：编辑器类型、分类存在性、标签存在性
     */
    private void validateSaveDTO(ArticleSaveDTO dto) {
        if (!EditorType.isValid(dto.getEditorType())) {
            throw new BusinessException(StatusCode.EDITOR_TYPE_INVALID);
        }
        if (Objects.nonNull(dto.getCategoryId())) {
            Category category = categoryMapper.selectById(dto.getCategoryId());
            if (Objects.isNull(category)) {
                throw new BusinessException(StatusCode.CATEGORY_NOT_EXIST);
            }
        }
        if (Objects.nonNull(dto.getTagIds()) && !dto.getTagIds().isEmpty()) {
            List<Tag> tags = tagMapper.selectByIds(dto.getTagIds());
            if (tags.size() != dto.getTagIds().stream().distinct().count()) {
                throw new BusinessException(StatusCode.TAG_NOT_EXIST);
            }
        }
    }

    /**
     * 新建时的 slug（issue #14）：显式指定走格式 + 唯一性校验后原样采用；
     * 缺省按标题清洗生成并消解冲突；清洗结果为空返回 null（调用方插入后回写 post-{id}）
     */
    private String resolveSlugForCreate(ArticleSaveDTO dto) {
        if (Objects.nonNull(dto.getSlug()) && !dto.getSlug().isBlank()) {
            String slug = dto.getSlug().trim();
            assertSlugFormat(slug);
            assertSlugAvailable(slug, null);
            return slug;
        }
        String base = ArticleSlugger.clean(dto.getTitle());
        if (base.isEmpty()) {
            return null;
        }
        return uniqueSlugFor(base, null);
    }

    /**
     * 显式 slug 的格式校验：小写字母/数字/连字符、≤200（口径与 article.slug 列一致）
     */
    private void assertSlugFormat(String slug) {
        if (!ArticleSlugger.isValid(slug)) {
            throw new BusinessException(StatusCode.ARTICLE_SLUG_INVALID);
        }
    }

    /**
     * slug 全局唯一校验（含回收站占位：selectIdBySlugAny 查 deleted=1 的行，
     * 见 V11 迁移注释）；excludeId 为编辑场景排除自身，新建传 null
     */
    private void assertSlugAvailable(String slug, Long excludeId) {
        Long holder = baseMapper.selectIdBySlugAny(slug);
        if (Objects.nonNull(holder) && !holder.equals(excludeId)) {
            throw new BusinessException(StatusCode.ARTICLE_SLUG_EXISTS);
        }
    }

    /**
     * 自动生成 slug 的冲突消解：基底按后缀长度截断（总量 ≤200），冲突依次追加 -2、-3…；
     * 个人博客量级实际不会撞满上限，并发与超界冲突由 uk_article_slug 唯一索引兜底
     */
    private String uniqueSlugFor(String base, Long excludeId) {
        String candidate = ArticleSlugger.truncateForSuffix(base, "");
        Long holder = baseMapper.selectIdBySlugAny(candidate);
        if (Objects.isNull(holder) || holder.equals(excludeId)) {
            return candidate;
        }
        for (int i = 2; i <= SLUG_SUFFIX_MAX; i++) {
            String suffix = "-" + i;
            candidate = ArticleSlugger.truncateForSuffix(base, suffix) + suffix;
            holder = baseMapper.selectIdBySlugAny(candidate);
            if (Objects.isNull(holder) || holder.equals(excludeId)) {
                return candidate;
            }
        }
        throw new BusinessException(StatusCode.ARTICLE_SLUG_EXISTS);
    }

    /**
     * 保存入参状态：可空，仅允许 DRAFT / PUBLISHED（下架走状态变更接口）
     */
    private String resolveSaveStatus(String status) {
        if (Objects.isNull(status) || status.isBlank()) {
            return ArticleStatus.DRAFT.name();
        }
        if (!ArticleStatus.DRAFT.name().equals(status) && !ArticleStatus.PUBLISHED.name().equals(status)) {
            throw new BusinessException(StatusCode.ARTICLE_STATUS_INVALID);
        }
        return status;
    }

    /**
     * 可见性入参解析（issue #15）：可空（新建缺省 PUBLIC；编辑由调用方先行判空保持现状），
     * 非法值抛 ARTICLE_VISIBILITY_INVALID
     */
    private String resolveVisibility(String visibility) {
        if (Objects.isNull(visibility) || visibility.isBlank()) {
            return Visibility.PUBLIC.name();
        }
        if (!Visibility.isValid(visibility)) {
            throw new BusinessException(StatusCode.ARTICLE_VISIBILITY_INVALID);
        }
        return visibility;
    }

    /**
     * 访问口令哈希解析（issue #15）：仅在落 PASSWORD 时参与——
     * dto 给了明文口令则校验长度后落 BCrypt 哈希；没给则沿用既有哈希（新建 / 从无口令
     * 状态切入 PASSWORD 时没有既有哈希可沿用，抛 ARTICLE_PASSWORD_REQUIRED）。
     * 非 PASSWORD 一律返回 null（切回 PUBLIC / PRIVATE 清空残留哈希）。
     * 调用前提：dto.visibility 已经 {@link #resolveVisibility} 归一。
     *
     * @param existing 编辑场景的库内现值（新建传 null）
     */
    private String resolvePasswordHash(ArticleSaveDTO dto, Article existing) {
        if (!Visibility.PASSWORD.name().equals(dto.getVisibility())) {
            return null;
        }
        String plain = dto.getAccessPassword();
        if (Objects.nonNull(plain) && !plain.isBlank()) {
            if (plain.length() < ACCESS_PASSWORD_MIN_LENGTH || plain.length() > ACCESS_PASSWORD_MAX_LENGTH) {
                throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                        "访问口令长度需为 " + ACCESS_PASSWORD_MIN_LENGTH + "~" + ACCESS_PASSWORD_MAX_LENGTH + " 个字符");
            }
            if (plain.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "访问口令 UTF-8 编码不能超过 72 字节");
            }
            return passwordEncoder.encode(plain);
        }
        String existingHash = Objects.nonNull(existing) ? existing.getAccessPasswordHash() : null;
        if (Objects.nonNull(existingHash) && !existingHash.isBlank()) {
            return existingHash;
        }
        throw new BusinessException(StatusCode.ARTICLE_PASSWORD_REQUIRED);
    }

    /**
     * 摘要缺省取 content 纯文本前 200 字（去 HTML 标签、压缩空白）
     */
    private String resolveSummary(ArticleSaveDTO dto) {
        if (Objects.nonNull(dto.getSummary()) && !dto.getSummary().isBlank()) {
            return dto.getSummary();
        }
        // 与 content_text 同一套清洗（P0 抽出的 toPlainText）
        String text = toPlainText(dto.getContent());
        return text.length() <= SUMMARY_MAX_LENGTH ? text : text.substring(0, SUMMARY_MAX_LENGTH);
    }

    private Article requireArticle(Long id) {
        Article article = getById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        return article;
    }

    /**
     * 仅作者本人或 ADMIN 可操作，否则 403
     */
    private void assertAuthorOrAdmin(Article article, Jwt jwt) {
        if (!isAuthorOrAdmin(article, jwt)) {
            throw new BusinessException(StatusCode.NOT_AUTHOR);
        }
    }

    private boolean isAuthorOrAdmin(Article article, Jwt jwt) {
        if (Objects.isNull(jwt)) {
            return false;
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        return userId.equals(article.getAuthorId()) || isAdmin();
    }

    /**
     * 判断当前请求主体是否为 ADMIN：以安全上下文中的权限为准（由
     * DatabaseRoleJwtAuthenticationConverter 按 sub 实时读库装配），不再解析 JWT 的
     * roles 声明——声明在签发时固化，库内直改角色后会失真（提拔 ADMIN 误拒 / 降级误放行）
     */
    private boolean isAdmin() {
        String adminAuthority = "ROLE_" + UserRole.ADMIN.name();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return Objects.nonNull(authentication) && authentication.getAuthorities().stream()
                .anyMatch(authority -> adminAuthority.equals(authority.getAuthority()));
    }

    /**
     * 全量替换文章的标签关联
     */
    private void replaceTagRelations(Long articleId, List<Long> tagIds) {
        articleTagMapper.delete(Wrappers.lambdaQuery(ArticleTag.class).eq(ArticleTag::getArticleId, articleId));
        if (Objects.isNull(tagIds) || tagIds.isEmpty()) {
            return;
        }
        tagIds.stream().distinct().forEach(tagId -> {
            ArticleTag relation = new ArticleTag();
            relation.setArticleId(articleId);
            relation.setTagId(tagId);
            articleTagMapper.insert(relation);
        });
    }

    /**
     * 填充详情的互动数据：浏览量 / 点赞收藏评论数 / 当前用户点赞收藏状态。
     * likeCount / favoriteCount / liked / favorited 经 interaction 模块端口实时查关联表，
     * commentCount 经 comment 模块端口按 NORMAL 口径计数，均不落列（issue #34 第三步）。
     */
    private void fillInteraction(ArticleDetailVO vo, Article article, boolean visible, Jwt jwt) {
        Long articleId = article.getId();
        // 可见详情刚自增过一次，内存值 +1 与库内一致；不可见（草稿/下架/定时中）展示当前值
        long viewCount = Objects.isNull(article.getViewCount()) ? 0L : article.getViewCount();
        vo.setViewCount(visible ? viewCount + 1 : viewCount);
        // 匿名 viewerId 传 null：liked / favorited 恒 false（端口语义，与收口前一致）
        Long viewerId = Objects.nonNull(jwt) ? JwtSubjects.userIdOf(jwt) : null;
        ArticleInteractionStats stats = articleInteractionPort.statsOf(articleId, viewerId);
        vo.setLikeCount(stats.likeCount());
        vo.setFavoriteCount(stats.favoriteCount());
        vo.setLiked(stats.liked());
        vo.setFavorited(stats.favorited());
        vo.setCommentCount(articleCommentStatsPort.countVisibleByArticleId(articleId));
    }

    /**
     * 批量组装列表 VO：作者 / 分类名 / 标签 内存联查，避免 N+1
     */
    @Override
    public List<ArticleListVO> assemble(List<Article> articles) {
        if (articles.isEmpty()) {
            return List.of();
        }
        List<ArticleListVO> vos = articles.stream()
                .map(article -> converter.convert(article, ArticleListVO.class))
                .toList();
        fillAssociations(new ArrayList<>(vos), articles);
        return vos;
    }

    /**
     * 为 VO 填充关联信息（作者 / 分类名 / 标签）与可见性标记。vos 与 articles 需按下标一一对应。
     */
    private void fillAssociations(List<? extends ArticleListVO> vos, List<Article> articles) {
        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        Map<Long, List<TagVO>> tagsMap = tagsOfArticles(articleIds);
        Set<Long> authorIds = articles.stream().map(Article::getAuthorId).collect(Collectors.toSet());
        Map<Long, User> authorMap = userService.listByIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<Long> categoryIds = articles.stream().map(Article::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectByIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        for (int i = 0; i < articles.size(); i++) {
            Article article = articles.get(i);
            ArticleListVO vo = vos.get(i);
            // 可见性标记：谓词单一出处 ArticleVisibility，所有走装配的列表/详情统一在此填充。
            // 公开列表恒 true（无副作用）；收藏夹（listByIdsPreserveOrder 不过滤可见性）里
            // 已下架 / 定时中的条目为 false，前端据此置灰
            vo.setVisible(ArticleVisibility.isVisible(article));
            vo.setCategoryName(Objects.nonNull(article.getCategoryId())
                    ? categoryNameMap.get(article.getCategoryId()) : null);
            vo.setTags(tagsMap.getOrDefault(article.getId(), List.of()));
            User author = authorMap.get(article.getAuthorId());
            if (Objects.nonNull(author)) {
                vo.setAuthor(toAuthorVO(author));
            }
        }
    }

    /**
     * 作者信息内存组装（不 SQL join）
     */
    private ArticleAuthorVO toAuthorVO(User user) {
        ArticleAuthorVO authorVO = new ArticleAuthorVO();
        authorVO.setId(user.getId());
        authorVO.setUsername(user.getUsername());
        authorVO.setNickname(user.getNickname());
        authorVO.setAvatar(user.getAvatar());
        return authorVO;
    }

    /**
     * 按文章id分组查询标签
     */
    private Map<Long, List<TagVO>> tagsOfArticles(List<Long> articleIds) {
        List<ArticleTag> relations = articleTagMapper.selectList(Wrappers.lambdaQuery(ArticleTag.class)
                .in(ArticleTag::getArticleId, articleIds));
        if (relations.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = relations.stream().map(ArticleTag::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tagMap = tagMapper.selectByIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        Map<Long, List<TagVO>> tagsMap = new HashMap<>();
        for (ArticleTag relation : relations) {
            Tag tag = tagMap.get(relation.getTagId());
            if (Objects.nonNull(tag)) {
                tagsMap.computeIfAbsent(relation.getArticleId(), k -> new ArrayList<>())
                        .add(converter.convert(tag, TagVO.class));
            }
        }
        return tagsMap;
    }
}

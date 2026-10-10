package com.zer0drv.blog.comment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleRef;
import com.zer0drv.blog.comment.domain.Comment;
import com.zer0drv.blog.comment.dto.CommentCreateDTO;
import com.zer0drv.blog.comment.enums.CommentSort;
import com.zer0drv.blog.comment.enums.CommentStatus;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import com.zer0drv.blog.comment.service.CommentCreateResult;
import com.zer0drv.blog.comment.service.CommentService;
import com.zer0drv.blog.comment.vo.CommentUserVO;
import com.zer0drv.blog.comment.vo.CommentVO;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.sensitive.SensitiveWordChecker;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.interaction.domain.CommentLike;
import com.zer0drv.blog.interaction.mapper.CommentLikeMapper;
import com.zer0drv.blog.site.service.SiteConfigService;
import com.zer0drv.blog.social.enums.NotificationType;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.enums.UserRole;
import com.zer0drv.blog.user.service.UserService;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    /**
     * 主评论内嵌的回复预览条数
     */
    private static final int REPLY_PREVIEW_SIZE = 3;

    /**
     * 评论内容最大长度
     */
    private static final int CONTENT_MAX_LENGTH = 1000;

    /**
     * 通知摘要长度：取评论内容前 50 字
     */
    private static final int NOTIFY_SUMMARY_MAX_LENGTH = 50;

    private final ArticleCatalog articleCatalog;
    private final CommentLikeMapper commentLikeMapper;
    private final UserService userService;
    private final Converter converter;
    private final NotificationService notificationService;
    private final SensitiveWordChecker sensitiveWordChecker;
    /**
     * 站点配置（P0 §2.1 审核开关）。实现类由 backend-C 提供，
     * 容器中没有实现类时（如 IT/合并前）走默认关闭兜底，必须 ObjectProvider 注入避免上下文启动失败
     */
    private final ObjectProvider<SiteConfigService> siteConfigServiceProvider;

    @Override
    public PageResult<CommentVO> pageRootComments(Long articleId, String sort, long page, long size, Jwt jwt) {
        CommentSort commentSort = CommentSort.of(sort);
        LambdaQueryWrapper<Comment> wrapper = Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getParentId, 0L)
                .eq(Comment::getStatus, CommentStatus.NORMAL.name());
        switch (commentSort) {
            // 热度排序：like_count 加权（ORDER BY like_count DESC, id DESC）
            case HOT -> wrapper.orderByDesc(Comment::getLikeCount).orderByDesc(Comment::getId);
            case TIME_ASC -> wrapper.orderByAsc(Comment::getId);
            default -> wrapper.orderByDesc(Comment::getId);
        }
        Page<Comment> result = page(new Page<>(page, size), wrapper);
        return PageResult.of(assemble(result.getRecords(), jwt, true), result.getTotal(), page, size);
    }

    @Override
    public PageResult<CommentVO> pageReplies(Long rootId, long page, long size, Jwt jwt) {
        Comment root = getById(rootId);
        if (Objects.isNull(root) || root.getParentId() != 0L) {
            throw new BusinessException(StatusCode.COMMENT_NOT_EXIST);
        }
        Page<Comment> result = page(new Page<>(page, size), Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getParentId, rootId)
                .eq(Comment::getStatus, CommentStatus.NORMAL.name())
                .orderByAsc(Comment::getId));
        return PageResult.of(assemble(result.getRecords(), jwt, false), result.getTotal(), page, size);
    }

    @Override
    public CommentCreateResult create(CommentCreateDTO dto, Jwt jwt) {
        String content = dto.getContent();
        if (Objects.isNull(content) || content.isBlank() || content.length() > CONTENT_MAX_LENGTH) {
            throw new BusinessException(StatusCode.COMMENT_CONTENT_INVALID);
        }
        // 前置校验：文章不存在（含已删除）与「存在但未发布」是两个独立错误码（API 语义）
        ArticleRef article = articleCatalog.findRef(dto.getArticleId())
                .orElseThrow(() -> new BusinessException(StatusCode.ARTICLE_NOT_EXIST));
        if (!articleCatalog.isVisible(article.id())) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_PUBLISHED);
        }
        // M5 敏感词过滤：命中则以 FOLDED 落库、不触发通知；判定结论随行返回，controller 按 status 覆盖提示
        boolean sensitiveHit = sensitiveWordChecker.containsSensitiveWord(content);
        // P0 §2.1 审核开关：开启时正常评论以 PENDING 落库、不触发通知
        boolean reviewRequired = !sensitiveHit && isCommentReviewRequired();
        CommentStatus status = sensitiveHit ? CommentStatus.FOLDED
                : (reviewRequired ? CommentStatus.PENDING : CommentStatus.NORMAL);
        Comment comment = new Comment();
        comment.setArticleId(dto.getArticleId());
        comment.setUserId(JwtSubjects.userIdOf(jwt));
        comment.setContent(content);
        comment.setLikeCount(0);
        comment.setStatus(status.name());
        if (Objects.isNull(dto.getParentId()) || dto.getParentId() == 0L) {
            // 主评论
            comment.setParentId(0L);
        } else {
            Comment parent = getById(dto.getParentId());
            // 父评论不存在或不属于同文章时，统一报不存在（不暴露其它文章的评论id）
            if (Objects.isNull(parent) || !parent.getArticleId().equals(dto.getArticleId())) {
                throw new BusinessException(StatusCode.COMMENT_NOT_EXIST);
            }
            // 严格两层：parentId 指向二级评论时归一化到其 root
            Long rootId = parent.getParentId() == 0L ? parent.getId() : parent.getParentId();
            comment.setParentId(rootId);
            // replyToUserId 非空时必须是本楼层合法参与者（root 作者或楼层内已有 NORMAL 回复的作者），
            // 否则显式拒绝，防止填写任意用户 ID 刷 MENTION 通知/邮件
            Long replyToUserId = dto.getReplyToUserId();
            if (Objects.nonNull(replyToUserId)) {
                Comment root = parent.getParentId() == 0L ? parent : getById(rootId);
                boolean participant = (Objects.nonNull(root) && replyToUserId.equals(root.getUserId()))
                        || count(Wrappers.lambdaQuery(Comment.class)
                                .eq(Comment::getParentId, rootId)
                                .eq(Comment::getStatus, CommentStatus.NORMAL.name())
                                .eq(Comment::getUserId, replyToUserId)) > 0;
                if (!participant) {
                    throw new BusinessException(StatusCode.PARAM_INVALID);
                }
                comment.setReplyToUserId(replyToUserId);
            }
        }
        save(comment);
        // M4 通知触发：主评论通知文章作者；回复按 COMMENT_REPLY + MENTION 组合规则（内部自己给自己不发、异常不回滚主业务）
        // M5：命中敏感词的评论（FOLDED）不产生通知
        // P0 §2.1：待审核评论（PENDING）不产生通知，审核通过（approve）时补发
        if (!sensitiveHit && !reviewRequired) {
            notifyCommentCreated(comment, article);
        }
        return new CommentCreateResult(comment.getId(), status);
    }

    /**
     * P0 §2.1 评论审核开关：SiteConfigService 实现缺失时默认关闭（兼容 IT 与合并前环境）
     */
    private boolean isCommentReviewRequired() {
        SiteConfigService siteConfigService = siteConfigServiceProvider.getIfAvailable();
        return Objects.nonNull(siteConfigService)
                && siteConfigService.getBool("comment.review_required", false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Jwt jwt) {
        Comment comment = getById(id);
        if (Objects.isNull(comment)) {
            throw new BusinessException(StatusCode.COMMENT_NOT_EXIST);
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        if (!comment.getUserId().equals(userId) && !isAdmin()) {
            throw new BusinessException(StatusCode.NOT_AUTHOR);
        }
        removeById(id);
        // 删主评论连带逻辑删除其回复
        if (comment.getParentId() == 0L) {
            remove(Wrappers.lambdaQuery(Comment.class).eq(Comment::getParentId, id));
        }
    }

    /**
     * M4 评论通知规则：
     * 主评论 → 文章作者收 COMMENT_REPLY；
     * 回复：replyToUserId 为空 → root 评论作者收 COMMENT_REPLY；
     * replyToUserId 非空 → root 作者收 COMMENT_REPLY + 被 @ 人收 MENTION（两者同人只发 MENTION）。
     * 自己给自己不发由 NotificationService.notify 兜底。
     * P0：提为接口 public 方法，供后台审核通过（approve）补发通知复用。
     */
    @Override
    public void notifyCommentCreated(Comment comment, ArticleRef article) {
        Long commenterId = comment.getUserId();
        String summary = comment.getContent().length() <= NOTIFY_SUMMARY_MAX_LENGTH
                ? comment.getContent() : comment.getContent().substring(0, NOTIFY_SUMMARY_MAX_LENGTH);
        if (comment.getParentId() == 0L) {
            notificationService.notify(article.authorId(), NotificationType.COMMENT_REPLY,
                    commenterId, article.id(), comment.getId(), summary, false);
            return;
        }
        Comment root = getById(comment.getParentId());
        Long rootAuthorId = Objects.nonNull(root) ? root.getUserId() : null;
        Long replyToUserId = comment.getReplyToUserId();
        if (Objects.isNull(replyToUserId)) {
            notificationService.notify(rootAuthorId, NotificationType.COMMENT_REPLY,
                    commenterId, article.id(), comment.getId(), summary, false);
            return;
        }
        if (Objects.equals(rootAuthorId, replyToUserId)) {
            notificationService.notify(replyToUserId, NotificationType.MENTION,
                    commenterId, article.id(), comment.getId(), summary, false);
            return;
        }
        notificationService.notify(rootAuthorId, NotificationType.COMMENT_REPLY,
                commenterId, article.id(), comment.getId(), summary, false);
        notificationService.notify(replyToUserId, NotificationType.MENTION,
                commenterId, article.id(), comment.getId(), summary, false);
    }

    /**
     * 批量组装评论 VO：用户 / 被回复人 / 点赞状态 / 回复数 内存联查，避免 N+1。
     * withReplies=true 时（主评论列表）内嵌前 3 条回复并填充 replyCount。
     */
    private List<CommentVO> assemble(List<Comment> comments, Jwt jwt, boolean withReplies) {
        if (comments.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> replyCountMap = Map.of();
        Map<Long, List<Comment>> previewMap = Map.of();
        List<Comment> previewReplies = List.of();
        if (withReplies) {
            List<Long> rootIds = comments.stream().map(Comment::getId).toList();
            replyCountMap = replyCounts(rootIds);
            // 一次查出本页主评论的全部 NORMAL 回复（时间正序），内存截取每楼前 3 条
            List<Comment> replies = list(Wrappers.lambdaQuery(Comment.class)
                    .in(Comment::getParentId, rootIds)
                    .eq(Comment::getStatus, CommentStatus.NORMAL.name())
                    .orderByAsc(Comment::getId));
            Map<Long, List<Comment>> grouped = replies.stream()
                    .collect(Collectors.groupingBy(Comment::getParentId, HashMap::new, Collectors.toList()));
            previewMap = new HashMap<>();
            List<Comment> previews = new ArrayList<>();
            for (Map.Entry<Long, List<Comment>> entry : grouped.entrySet()) {
                List<Comment> top = entry.getValue().stream().limit(REPLY_PREVIEW_SIZE).toList();
                previewMap.put(entry.getKey(), top);
                previews.addAll(top);
            }
            previewReplies = previews;
        }
        // 批量查用户（评论人 + 被回复人）
        List<Comment> all = new ArrayList<>(comments);
        all.addAll(previewReplies);
        Set<Long> userIds = all.stream()
                .flatMap(c -> Stream.of(c.getUserId(), c.getReplyToUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        // 批量查当前登录人的点赞状态（匿名一律 false）
        Set<Long> likedIds = likedCommentIds(all.stream().map(Comment::getId).collect(Collectors.toSet()), jwt);
        // 回复 VO（不嵌套、不计 replyCount）
        Map<Long, List<CommentVO>> previewVoMap = new HashMap<>();
        for (Map.Entry<Long, List<Comment>> entry : previewMap.entrySet()) {
            previewVoMap.put(entry.getKey(), entry.getValue().stream()
                    .map(reply -> toVO(reply, userMap, likedIds))
                    .collect(Collectors.toList()));
        }
        Map<Long, Long> finalReplyCountMap = replyCountMap;
        return comments.stream().map(comment -> {
            CommentVO vo = toVO(comment, userMap, likedIds);
            if (withReplies) {
                vo.setReplyCount(finalReplyCountMap.getOrDefault(comment.getId(), 0L));
                vo.setReplies(previewVoMap.getOrDefault(comment.getId(), List.of()));
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 单条评论转 VO 并填充 user / replyToUser / liked
     */
    private CommentVO toVO(Comment comment, Map<Long, User> userMap, Set<Long> likedIds) {
        CommentVO vo = converter.convert(comment, CommentVO.class);
        User user = userMap.get(comment.getUserId());
        if (Objects.nonNull(user)) {
            vo.setUser(toUserVO(user));
        }
        if (Objects.nonNull(comment.getReplyToUserId())) {
            User replyToUser = userMap.get(comment.getReplyToUserId());
            if (Objects.nonNull(replyToUser)) {
                vo.setReplyToUser(toUserVO(replyToUser));
            }
        }
        vo.setLiked(likedIds.contains(comment.getId()));
        return vo;
    }

    /**
     * 批量查询当前登录人点过赞的评论id；匿名返回空集
     */
    private Set<Long> likedCommentIds(Set<Long> commentIds, Jwt jwt) {
        if (Objects.isNull(jwt) || commentIds.isEmpty()) {
            return Set.of();
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        return commentLikeMapper.selectList(Wrappers.lambdaQuery(CommentLike.class)
                        .eq(CommentLike::getUserId, userId)
                        .in(CommentLike::getCommentId, commentIds))
                .stream().map(CommentLike::getCommentId).collect(Collectors.toSet());
    }

    /**
     * 批量统计各主评论的 NORMAL 回复数
     */
    private Map<Long, Long> replyCounts(List<Long> rootIds) {
        if (rootIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = baseMapper.selectMaps(new QueryWrapper<Comment>()
                .select("parent_id AS parentId", "COUNT(*) AS cnt")
                .eq("status", CommentStatus.NORMAL.name())
                .in("parent_id", rootIds)
                .groupBy("parent_id"));
        Map<Long, Long> countMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            // H2（IT 库）未加引号的列别名一律大写（PARENTID/CNT），MySQL 保留原样，按大小写不敏感取值兼容两者
            Long parentId = null;
            Long cnt = null;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("parentId".equalsIgnoreCase(entry.getKey())) {
                    parentId = ((Number) entry.getValue()).longValue();
                } else if ("cnt".equalsIgnoreCase(entry.getKey())) {
                    cnt = ((Number) entry.getValue()).longValue();
                }
            }
            if (Objects.nonNull(parentId) && Objects.nonNull(cnt)) {
                countMap.put(parentId, cnt);
            }
        }
        return countMap;
    }

    /**
     * 用户信息内存组装（不 SQL join）
     */
    private CommentUserVO toUserVO(User user) {
        CommentUserVO userVO = new CommentUserVO();
        userVO.setId(user.getId());
        userVO.setUsername(user.getUsername());
        userVO.setNickname(user.getNickname());
        userVO.setAvatar(user.getAvatar());
        return userVO;
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
}

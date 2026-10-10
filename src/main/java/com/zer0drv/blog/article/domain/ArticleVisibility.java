package com.zer0drv.blog.article.domain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zer0drv.blog.article.enums.ArticleStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 文章「对外可见」谓词的唯一出处（SPEC §1.3）。原同构判定散布在
 * article / seo / comment / interaction / social 五处，靠复制粘贴维持一致，全部收编于此。
 *
 * <p>规则：{@code status = PUBLISHED 且 publish_time 非空且不晚于当前时间}。
 * <ul>
 *   <li>DRAFT（草稿）/ OFFLINE（下架）一律不可见；</li>
 *   <li>PUBLISHED 但 publish_time 在未来 = 定时发布未到点，对公众不可见；</li>
 *   <li>publish_time 为 NULL 视为未发布，不可见；</li>
 *   <li>逻辑删除（deleted = 1）不在本类内存判定之列：MyBatis-Plus {@code @TableLogic}
 *       会在 Wrapper / Mapper 查询上自动拼 {@code deleted = 0}，被删行根本到不了内存；
 *       注解手写的原生 SQL 不经 MP 拦截，须拼 {@link #SQL} 常量（已显式含 {@code deleted = 0}）。</li>
 * </ul>
 *
 * <p>同一规则的三种消费形态：
 * <ul>
 *   <li>{@link #isVisible}：内存实体判定（详情 / 导出 / 评论 / 点赞收藏前置校验）；</li>
 *   <li>{@link #apply}：把等价条件拼到 {@link LambdaQueryWrapper}
 *       （status eq + publishTime isNotNull + le(now)）。SQL 中 le 对 NULL 求值为 UNKNOWN
 *       本就会排除空值，isNotNull 显式声明只为自证语义，与 {@link #SQL} 逐字对齐；</li>
 *   <li>{@link #SQL}：编译期常量，供 Mapper 注解 SQL 拼接。</li>
 * </ul>
 *
 * <p>{@link Clock} 参数使「定时发布到点翻转可见性」可用固定时钟单测；
 * 无参重载走系统时钟（线上路径）。纯静态工具类，无 Spring 依赖。
 */
public final class ArticleVisibility {

    /**
     * 原生 SQL 可见性片段（编译期常量，供 Mapper 注解拼接）。
     * 注解 SQL 绕过 MP 逻辑删除拦截，故显式带上 {@code deleted = 0}。
     */
    public static final String SQL =
            "status = 'PUBLISHED' AND publish_time IS NOT NULL AND publish_time <= NOW() AND deleted = 0";

    private ArticleVisibility() {
    }

    /**
     * 内存判定：article 对外是否可见。null 实体按不可见处理（调用方一般已先判空）。
     */
    public static boolean isVisible(Article article, Clock clock) {
        return Objects.nonNull(article)
                && ArticleStatus.PUBLISHED.name().equals(article.getStatus())
                && Objects.nonNull(article.getPublishTime())
                && !article.getPublishTime().isAfter(LocalDateTime.now(clock));
    }

    /**
     * 系统时钟便捷重载。
     */
    public static boolean isVisible(Article article) {
        return isVisible(article, Clock.systemDefaultZone());
    }

    /**
     * 把可见性谓词拼到 Wrapper 上（status eq + publishTime isNotNull + le(now)），
     * 原地修改并返回同一实例以接续链式调用；MP 逻辑删除自动追加 deleted = 0。
     */
    public static <T extends LambdaQueryWrapper<Article>> T apply(T wrapper, Clock clock) {
        wrapper.eq(Article::getStatus, ArticleStatus.PUBLISHED.name())
                .isNotNull(Article::getPublishTime)
                .le(Article::getPublishTime, LocalDateTime.now(clock));
        return wrapper;
    }

    /**
     * 系统时钟便捷重载。
     */
    public static <T extends LambdaQueryWrapper<Article>> T apply(T wrapper) {
        return apply(wrapper, Clock.systemDefaultZone());
    }
}

package com.zer0drv.blog.comment.api;

import java.util.Optional;

/**
 * 评论点赞数据端口（issue #34 第一步）：comment_like 关联与 comment.like_count
 * 冗余计数的唯一写入口，由 comment 模块实现。
 *
 * <p>interaction 模块的点赞 / 取消点赞流程不再直注 {@code CommentMapper} /
 * {@code CommentLikeMapper}：评论存在性与可见性判定、点赞落库、计数维护全部
 * 收口到本端口，原子 {@code like_count ± 1} SQL 留在 comment 模块内。
 *
 * <p>实现语义与收口前逐字节等价：
 * <ul>
 *   <li>点赞幂等：已赞（含并发唯一索引兜底）静默成功，不重复累加计数；</li>
 *   <li>取消点赞：确实删到一条关联才扣减计数（{@code like_count > 0} 兜底防负数）。</li>
 * </ul>
 *
 * <p>本包零 MyBatis-Plus 类型；方法同步执行于调用方事务内。
 */
public interface CommentLikePort {

    /**
     * 查可点赞评论的引用投影。仅 {@code status=NORMAL} 的评论对外可见：
     * 不存在 / FOLDED / PENDING 一律返回空（调用方按「评论不存在」处理，
     * 不暴露隐藏评论的存在性）。
     */
    Optional<CommentRef> findLikeable(long commentId);

    /**
     * 点赞：插入 comment_like 关联并把 comment.like_count 原子 +1。
     *
     * @return true = 本次为新赞（计数已 +1，调用方可发通知）；
     *         false = 已赞过（幂等静默，含并发下唯一索引兜底，计数不变）
     */
    boolean addLike(long commentId, long userId);

    /**
     * 取消点赞：物理删除 comment_like 关联；确实删到一条才把
     * comment.like_count 原子 -1（{@code like_count > 0} 兜底防负数）。
     * 未赞过时为空操作。
     */
    void removeLike(long commentId, long userId);
}

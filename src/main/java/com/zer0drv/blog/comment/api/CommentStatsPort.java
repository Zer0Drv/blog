package com.zer0drv.blog.comment.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论统计读端口（issue #34 第五步）：管理端全站统计的评论维度口径，
 * 由拥有 comment 表的 comment 模块自己实现。
 *
 * <p>与 {@link CommentLikePort} 同款模式：窄契约、零 MyBatis-Plus 类型，
 * 外模块单测 mock 本接口即可。口径与收口前 AdminStatsServiceImpl 的内联查询
 * 逐字节等价；方法同步执行于调用方上下文中，只读，无事务要求。
 */
public interface CommentStatsPort {

    /**
     * 对外可见评论总数：仅 {@code status=NORMAL}（FOLDED / PENDING 不计，
     * 逻辑删除由 MP 全局过滤）。
     */
    long countNormal();

    /**
     * create_time 不早于 since 的评论数（管理端「今日新增评论」口径：
     * 任意状态，仅按时间过滤）。
     */
    long countCreatedSince(LocalDateTime since);

    /**
     * 最新评论投影（id 倒序，至多 limit 条）。管理端审核视角：
     * 任意状态均透出，不按可见性过滤。
     */
    List<CommentStatsEntry> listLatest(int limit);
}

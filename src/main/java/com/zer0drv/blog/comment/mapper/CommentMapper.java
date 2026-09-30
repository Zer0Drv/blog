package com.zer0drv.blog.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.comment.domain.Comment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @author Yoruhaki
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * P0 §2.4 评论回收站分页：查 deleted=1 的行。
     * MP 全局逻辑删除会自动拼 deleted=0，Wrapper 绕不过，必须手写 SQL（保留 keyword/articleId 过滤）。
     */
    @Select({"<script>",
            "SELECT * FROM `comment` WHERE deleted = 1",
            "<if test='keyword != null and keyword != \"\"'> AND content LIKE CONCAT('%', #{keyword}, '%')</if>",
            "<if test='articleId != null'> AND article_id = #{articleId}</if>",
            "ORDER BY id DESC",
            "</script>"})
    Page<Comment> pageTrash(Page<Comment> page, @Param("keyword") String keyword, @Param("articleId") Long articleId);

    /**
     * 按 id 查回收站中的评论（deleted=1），找不到返回 null
     */
    @Select("SELECT * FROM `comment` WHERE id = #{id} AND deleted = 1")
    Comment selectDeletedById(@Param("id") Long id);

    /**
     * 按 id 查评论（不区分 deleted），物理删除前存在性校验用
     */
    @Select("SELECT * FROM `comment` WHERE id = #{id}")
    Comment selectAnyById(@Param("id") Long id);

    /**
     * 回收站恢复：置 deleted=0、status=NORMAL
     */
    @Update("UPDATE `comment` SET deleted = 0, status = 'NORMAL' WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") Long id);

    /**
     * 主评论恢复时连带恢复其 deleted=1 的回复
     * （已知近似：无法区分回复是随楼主删还是单独删，一律连带恢复）
     */
    @Update("UPDATE `comment` SET deleted = 0, status = 'NORMAL' WHERE parent_id = #{rootId} AND deleted = 1")
    int restoreRepliesByRootId(@Param("rootId") Long rootId);

    /**
     * 主评论全部回复的 id 列表（不区分 deleted，彻底删除时清理 comment_like 用）
     */
    @Select("SELECT id FROM `comment` WHERE parent_id = #{rootId}")
    List<Long> selectReplyIdsByRootId(@Param("rootId") Long rootId);

    /**
     * 物理删除评论本行（回收站「彻底删除」）
     */
    @Delete("DELETE FROM `comment` WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /**
     * 物理删除主评论的全部回复（不区分 deleted）
     */
    @Delete("DELETE FROM `comment` WHERE parent_id = #{rootId}")
    int physicalDeleteRepliesByRootId(@Param("rootId") Long rootId);
}

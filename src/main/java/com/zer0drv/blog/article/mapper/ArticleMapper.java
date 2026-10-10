package com.zer0drv.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleVisibility;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author Yoruhaki
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 作者本人回收站分页（P0）。MP 逻辑删除会自动拼 deleted=0，回收站必须手写 SQL 绕过
     */
    @Select("SELECT * FROM article WHERE deleted = 1 AND author_id = #{authorId} ORDER BY update_time DESC")
    Page<Article> selectTrashPage(Page<Article> page, @Param("authorId") Long authorId);

    /**
     * 管理端全用户回收站分页（P0），保留 keyword（title/summary 模糊）与 authorId 过滤
     */
    @Select("""
            <script>
            SELECT * FROM article WHERE deleted = 1
            <if test="authorId != null"> AND author_id = #{authorId}</if>
            <if test="keyword != null and keyword != ''">
              AND (title LIKE CONCAT('%', #{keyword}, '%') OR summary LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY update_time DESC
            </script>
            """)
    Page<Article> selectAdminTrashPage(Page<Article> page, @Param("keyword") String keyword,
                                       @Param("authorId") Long authorId);

    /**
     * 查回收站中的文章（仅 deleted=1；恢复用，找不到即不在回收站）
     */
    @Select("SELECT * FROM article WHERE id = #{id} AND deleted = 1")
    Article selectDeletedById(@Param("id") Long id);

    /**
     * 按 id 查文章（含已逻辑删除的；彻底删除前的存在性与归属校验用）
     */
    @Select("SELECT * FROM article WHERE id = #{id}")
    Article selectAnyById(@Param("id") Long id);

    /**
     * 从回收站恢复：置 deleted=0 且回草稿态（不直接上线）
     */
    @Update("UPDATE article SET deleted = 0, status = 'DRAFT' WHERE id = #{id} AND deleted = 1")
    int restoreDeleted(@Param("id") Long id);

    /**
     * 物理删除文章本行（彻底删除用，绕过 MP 逻辑删除）
     */
    @Delete("DELETE FROM article WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /**
     * 全文搜索（P0，仅 MySQL 跑；H2 不支持 MATCH...AGAINST，测试走 LIKE 兜底）
     */
    @Select("SELECT * FROM article " +
            "WHERE MATCH(title, content_text) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) " +
            "AND " + ArticleVisibility.SQL + " " +
            "ORDER BY publish_time DESC")
    Page<Article> searchByFulltext(Page<Article> page, @Param("keyword") String keyword);
}

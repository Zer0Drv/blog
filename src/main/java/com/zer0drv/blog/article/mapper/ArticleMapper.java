package com.zer0drv.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleSearchCriteria;
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

    @Select("SELECT * FROM article WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Article selectForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM article WHERE id = #{id} FOR UPDATE")
    Article selectAnyForUpdate(@Param("id") Long id);

    // Updating an editing token must not change the effective publication's timestamp.
    @Update("UPDATE article SET edit_revision=edit_revision+1, update_time=update_time WHERE id=#{id} AND edit_revision=#{revision}")
    int advanceEditRevision(@Param("id") Long id, @Param("revision") Long revision);

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
     * 按 slug 查持有者 id（含已逻辑删除的行；slug 唯一性校验用——回收站文章仍占位，
     * 见 V11 迁移注释）。MP 逻辑删除会拼 deleted=0，故手写 SQL 绕过
     */
    @Select("SELECT id FROM article WHERE slug = #{slug} LIMIT 1")
    Long selectIdBySlugAny(@Param("slug") String slug);

    /**
     * 从回收站恢复：置 deleted=0 且回草稿态（不直接上线）
     */
    @Update("UPDATE article SET deleted = 0, status = 'DRAFT', edit_revision=edit_revision+1 WHERE id = #{id} AND deleted = 1")
    int restoreDeleted(@Param("id") Long id);

    /**
     * 物理删除文章本行（彻底删除用，绕过 MP 逻辑删除）
     */
    @Delete("DELETE FROM article WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /** Shared visibility/filter/count query for FULLTEXT and literal LIKE. No user SQL fragments. */
    @Select("<script>SELECT id, title, slug, summary, content_text, editor_type, cover, category_id, author_id, "
            + "status, visibility, publish_time, view_count, is_top, is_recommended, create_time, update_time "
            + "FROM article WHERE <![CDATA[" + ArticleVisibility.SQL + "]]> " + """
            <if test="q.keyword != ''">
              <choose>
                <when test="q.fulltext">
                  AND MATCH(title, content_text) AGAINST(#{q.keyword} IN NATURAL LANGUAGE MODE)
                </when>
                <otherwise>
                  AND (title LIKE #{q.likePattern} ESCAPE '!'
                    OR summary LIKE #{q.likePattern} ESCAPE '!'
                    OR content_text LIKE #{q.likePattern} ESCAPE '!')
                </otherwise>
              </choose>
            </if>
            <if test="q.categoryId != null">AND category_id = #{q.categoryId}</if>
            <if test="q.tagId != null">
              AND EXISTS (SELECT 1 FROM article_tag atag WHERE atag.article_id = article.id AND atag.tag_id = #{q.tagId})
            </if>
            <if test="q.publishedFrom != null">AND publish_time &gt;= #{q.publishedFrom}</if>
            <if test="q.publishedUntil != null">AND publish_time &lt; #{q.publishedUntil}</if>
            ORDER BY
            <choose>
              <when test="q.effectiveSort == 'relevance'">
                MATCH(title, content_text) AGAINST(#{q.keyword} IN NATURAL LANGUAGE MODE) DESC,
                publish_time DESC, id DESC
              </when>
              <when test="q.effectiveSort == 'oldest'">publish_time ASC, id ASC</when>
              <otherwise>publish_time DESC, id DESC</otherwise>
            </choose>
            </script>
            """)
    Page<Article> searchPublic(Page<Article> page, @Param("q") ArticleSearchCriteria criteria);
}

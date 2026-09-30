package com.zer0drv.blog.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zer0drv.blog.article.domain.ArticleVersion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @author Yoruhaki
 */
@Mapper
public interface ArticleVersionMapper extends BaseMapper<ArticleVersion> {

    /**
     * 物理删除某文章的全部版本快照（彻底删除文章时级联，绕过 MP 逻辑删除）
     */
    @Delete("DELETE FROM article_version WHERE article_id = #{articleId}")
    int physicalDeleteByArticleId(@Param("articleId") Long articleId);
}

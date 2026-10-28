package com.zer0drv.blog.article.mapper;

import com.zer0drv.blog.article.domain.Article;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** Access is serialized by the parent article row lock. */
@Repository
@RequiredArgsConstructor
public class ArticleWorkingCopyRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record Copy(Article article, List<Long> tagIds, LocalDateTime updatedAt) { }

    public Copy find(long id) {
        var rows = jdbc.query("SELECT * FROM article_working_copy WHERE article_id=?", (rs, index) -> {
            Article article = new Article();
            article.setId(id);
            article.setTitle(rs.getString("title"));
            article.setSlug(rs.getString("slug"));
            article.setSummary(rs.getString("summary"));
            article.setContent(rs.getString("content"));
            article.setEditorType(rs.getString("editor_type"));
            article.setCover(rs.getString("cover"));
            article.setCategoryId(rs.getObject("category_id", Long.class));
            article.setVisibility(rs.getString("visibility"));
            article.setAccessPasswordHash(rs.getString("access_password_hash"));
            Timestamp publishTime = rs.getTimestamp("publish_time");
            article.setPublishTime(publishTime == null ? null : publishTime.toLocalDateTime());
            List<Long> tags = json.readValue(rs.getString("tag_ids"), new tools.jackson.core.type.TypeReference<List<Long>>() { });
            return new Copy(article, tags, rs.getTimestamp("update_time").toLocalDateTime());
        }, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void replace(Article article, List<Long> tagIds) {
        delete(article.getId());
        jdbc.update("INSERT INTO article_working_copy(article_id,title,slug,summary,content,editor_type,cover,category_id,tag_ids,visibility,access_password_hash,publish_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                article.getId(), article.getTitle(), article.getSlug(), article.getSummary(), article.getContent(),
                article.getEditorType(), article.getCover(), article.getCategoryId(), json.writeValueAsString(tagIds),
                article.getVisibility(), article.getAccessPasswordHash(), article.getPublishTime());
    }

    public void delete(long id) { jdbc.update("DELETE FROM article_working_copy WHERE article_id=?", id); }

    public boolean exists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM article_working_copy WHERE article_id=?", Long.class, id) > 0;
    }
}

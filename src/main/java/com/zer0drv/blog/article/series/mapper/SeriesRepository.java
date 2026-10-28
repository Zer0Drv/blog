package com.zer0drv.blog.article.series.mapper;

import com.zer0drv.blog.article.api.SeriesLink;
import com.zer0drv.blog.article.domain.ArticleVisibility;
import com.zer0drv.blog.article.series.dto.SeriesSaveDTO;
import com.zer0drv.blog.article.series.vo.SeriesViews.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit SQL keeps locking reads and ordered replacements portable between MySQL and H2. */
@Repository
@RequiredArgsConstructor
public class SeriesRepository {
    private final JdbcTemplate jdbc;

    // No copied visibility rules: all public chapter queries use the article domain's predicate.
    private static final String VISIBLE_IDS = "SELECT id FROM article WHERE " + ArticleVisibility.SQL;
    private static final String SERIES_SELECT = "SELECT s.*, COALESCE(NULLIF(u.nickname,''),u.username,'') owner_name, "
            + "(SELECT COUNT(*) FROM article_series_item i WHERE i.series_id=s.id AND i.article_id IN ("
            + VISIBLE_IDS + ")) article_count FROM article_series s LEFT JOIN `user` u ON u.id=s.owner_id ";
    private static final String MANAGED_ARTICLES = "SELECT id,slug,title,publish_time,status,visibility,"
            + "CASE WHEN " + ArticleVisibility.SQL + " THEN TRUE ELSE FALSE END visible FROM article WHERE deleted=0";
    private static final RowMapper<Chapter> CHAPTER = (rs, row) -> new Chapter(rs.getLong("id"),
            rs.getString("slug"), rs.getString("title"), time(rs, "publish_time"));
    private static final RowMapper<ManagedChapter> MANAGED_CHAPTER = (rs, row) -> new ManagedChapter(rs.getLong("id"),
            rs.getString("slug"), rs.getString("title"), time(rs, "publish_time"),
            rs.getString("status"), rs.getString("visibility"), rs.getBoolean("visible"));
    private static final RowMapper<SeriesRow> SERIES = (rs, row) -> new SeriesRow(rs.getLong("id"),
            rs.getLong("owner_id"), rs.getString("owner_name"), rs.getString("title"), rs.getString("description"),
            rs.getString("cover"), rs.getString("slug"), rs.getString("status"), rs.getLong("revision"),
            rs.getLong("article_count"), time(rs, "update_time"));

    public record SeriesRow(long id, long ownerId, String ownerName, String title, String description,
                            String cover, String slug, String status, long revision, long articleCount,
                            LocalDateTime updateTime) { }

    public List<SeriesRow> list(boolean published, Long ownerId, long offset, long size) {
        String where = published ? "WHERE s.status='PUBLISHED' " : "WHERE 1=1 ";
        List<Object> args = new ArrayList<>();
        if (ownerId != null) { where += "AND s.owner_id=? "; args.add(ownerId); }
        args.add(size);
        args.add(offset);
        return jdbc.query(SERIES_SELECT + where + "ORDER BY s.update_time DESC,s.id DESC LIMIT ? OFFSET ?", SERIES, args.toArray());
    }

    public long count(boolean published, Long ownerId) {
        String sql = "SELECT COUNT(*) FROM article_series WHERE " + (published ? "status='PUBLISHED'" : "1=1");
        return ownerId == null ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql + " AND owner_id=?", Long.class, ownerId);
    }

    public SeriesRow find(long id) { return first(jdbc.query(SERIES_SELECT + "WHERE s.id=?", SERIES, id)); }

    public SeriesRow findPublished(String slug) {
        return first(jdbc.query(SERIES_SELECT + "WHERE s.slug=? AND s.status='PUBLISHED'", SERIES, slug));
    }

    public SeriesRow lock(long id) {
        // The locking read observes the winning revision under MySQL REPEATABLE READ.
        var rows = jdbc.query("SELECT id,owner_id,revision FROM article_series WHERE id=? FOR UPDATE",
                (rs, row) -> new SeriesRow(rs.getLong("id"), rs.getLong("owner_id"), null, null, null,
                        null, null, null, rs.getLong("revision"), 0, null), id);
        return first(rows);
    }

    public List<Chapter> publicChapters(long seriesId) {
        return jdbc.query("SELECT a.id,a.slug,a.title,a.publish_time FROM article_series_item i JOIN "
                + "(SELECT id,slug,title,publish_time FROM article WHERE " + ArticleVisibility.SQL + ") a ON a.id=i.article_id "
                + "WHERE i.series_id=? ORDER BY i.position", CHAPTER, seriesId);
    }

    public List<ManagedChapter> managedChapters(long seriesId) {
        return jdbc.query("SELECT a.* FROM article_series_item i JOIN (" + MANAGED_ARTICLES + ") a ON a.id=i.article_id "
                + "WHERE i.series_id=? ORDER BY i.position", MANAGED_CHAPTER, seriesId);
    }

    public boolean sourceVisible(long articleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE id=? AND " + ArticleVisibility.SQL, Long.class, articleId) > 0;
    }

    public List<SeriesRow> memberships(long articleId) {
        return jdbc.query(SERIES_SELECT + "WHERE s.status='PUBLISHED' AND s.id IN "
                + "(SELECT series_id FROM article_series_item WHERE article_id=?) ORDER BY s.id", SERIES, articleId);
    }

    public List<ManagedChapter> candidates(long ownerId, String keyword, long offset, long size) {
        return jdbc.query(MANAGED_ARTICLES + " AND author_id=? AND LOWER(title) LIKE ? ESCAPE '!' "
                        + "ORDER BY update_time DESC,id DESC LIMIT ? OFFSET ?", MANAGED_CHAPTER,
                ownerId, searchPattern(keyword), size, offset);
    }

    public long candidateCount(long ownerId, String keyword) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE deleted=0 AND author_id=? "
                + "AND LOWER(title) LIKE ? ESCAPE '!'", Long.class, ownerId, searchPattern(keyword));
    }

    public boolean lockOwnedArticles(long ownerId, List<Long> ids) {
        if (ids.isEmpty()) return true;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        args.add(ownerId);
        // Lock in stable ID order, so deletion cannot insert an orphan membership after validation.
        return jdbc.queryForList("SELECT id FROM article WHERE id IN (" + placeholders
                + ") AND author_id=? AND deleted=0 ORDER BY id FOR UPDATE", Long.class, args.toArray()).size() == ids.size();
    }

    public long insert(long ownerId, SeriesSaveDTO dto) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO article_series "
                    + "(owner_id,title,description,cover,slug,status) VALUES (?,?,?,?,?,?)", new String[]{"id"});
            statement.setLong(1, ownerId);
            statement.setString(2, dto.title().trim());
            statement.setString(3, blank(dto.description()));
            statement.setString(4, blank(dto.cover()));
            statement.setString(5, dto.slug());
            statement.setString(6, dto.status());
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public int update(long id, SeriesSaveDTO dto) {
        return jdbc.update("UPDATE article_series SET title=?,description=?,cover=?,slug=?,status=?,"
                        + "revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=? AND revision=?",
                dto.title().trim(), blank(dto.description()), blank(dto.cover()), dto.slug(), dto.status(), id, dto.revision());
    }

    public void replaceChapters(long id, List<Long> articleIds) {
        jdbc.update("DELETE FROM article_series_item WHERE series_id=?", id);
        if (articleIds.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < articleIds.size(); index++) rows.add(new Object[]{id, articleIds.get(index), index});
        jdbc.batchUpdate("INSERT INTO article_series_item(series_id,article_id,position) VALUES (?,?,?)", rows);
    }

    public int delete(long id, long revision) {
        jdbc.update("DELETE FROM article_series_item WHERE series_id=?", id);
        return jdbc.update("DELETE FROM article_series WHERE id=? AND revision=?", id, revision);
    }

    public void purgeArticle(long articleId) { jdbc.update("DELETE FROM article_series_item WHERE article_id=?", articleId); }

    public List<SeriesLink> publishedLinks() {
        return jdbc.query("SELECT id,slug,update_time FROM article_series WHERE status='PUBLISHED' ORDER BY id LIMIT 1000",
                (rs, row) -> new SeriesLink(rs.getLong("id"), rs.getString("slug"), time(rs, "update_time")));
    }

    private static String searchPattern(String keyword) {
        return "%" + keyword.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String blank(String value) { return value == null ? "" : value.trim(); }
    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
}

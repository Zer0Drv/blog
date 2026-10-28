package com.zer0drv.blog.article.service.impl;

import com.zer0drv.blog.article.api.ArticleAttachmentReferences;
import com.zer0drv.blog.attachment.service.AttachmentObjectKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ArticleAttachmentReferencesImpl implements ArticleAttachmentReferences {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Override
    public Set<String> retainedAttachmentFilenames() {
        Set<String> references = new HashSet<>();
        // No deleted / visibility filter: trash and retained versions remain restorable.
        for (String table : new String[]{"article", "article_version", "article_working_copy"}) {
            jdbc.query("SELECT content, cover, summary FROM " + table, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                    references.addAll(AttachmentObjectKeys.references(row.getString(1), row.getString(2), row.getString(3))));
        }
        // Series have no recycle bin: all retained collections, including drafts, protect their cover.
        jdbc.query("SELECT cover FROM article_series", (org.springframework.jdbc.core.RowCallbackHandler) row ->
                references.addAll(AttachmentObjectKeys.references(row.getString(1))));
        int keys = 0;
        try (var cursor = redis.scan(ScanOptions.scanOptions().match("blog:autosave:*").count(200).build())) {
            while (cursor.hasNext()) {
                if (++keys > 100_000) throw new IllegalStateException("草稿超过扫描上限");
                String value = redis.opsForValue().get(cursor.next());
                // Expired during SCAN is safe; malformed / unavailable Redis must fail closed.
                if (value != null) references.addAll(AttachmentObjectKeys.references(mapper.readValue(value, Map.class).toString()));
            }
        }
        return references;
    }
}

package com.zer0drv.blog.attachment.service;

import com.zer0drv.blog.article.api.ArticleAttachmentReferences;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttachmentReferenceSnapshot {
    private final JdbcTemplate jdbc;
    private final ArticleAttachmentReferences articles;

    public Set<String> load() {
        Set<String> references = new HashSet<>(articles.retainedAttachmentFilenames());
        // An item still in someone's library is owned, even if not embedded in published content.
        String[] queries = {
                "SELECT object_key FROM attachment WHERE deleted=0",
                "SELECT url FROM attachment WHERE deleted=0",
                "SELECT content FROM comment", "SELECT content FROM custom_page",
                "SELECT avatar FROM `user`", "SELECT bio FROM `user`",
                "SELECT config_value FROM site_config", "SELECT logo FROM friend_link",
                "SELECT url FROM friend_link", "SELECT content FROM private_message"
        };
        for (String sql : queries) jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                references.addAll(AttachmentObjectKeys.references(row.getString(1))));
        return references;
    }
}

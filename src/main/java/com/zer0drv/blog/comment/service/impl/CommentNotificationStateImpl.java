package com.zer0drv.blog.comment.service.impl;

import com.zer0drv.blog.comment.api.CommentNotificationState;
import com.zer0drv.blog.comment.mapper.CommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CommentNotificationStateImpl implements CommentNotificationState {
    private final CommentMapper comments;
    private final JdbcTemplate jdbc;

    public String state(Long id) {
        var comment = comments.selectAnyById(id);
        if (comment != null && Short.valueOf((short) 0).equals(comment.getDeleted())
                && "NORMAL".equals(comment.getStatus())) return "AVAILABLE";
        var reasons = jdbc.queryForList("SELECT reason FROM comment_notification_removal WHERE comment_id=?", String.class, id);
        if (!reasons.isEmpty()) return reasons.getFirst();
        return comment == null || Short.valueOf((short) 1).equals(comment.getDeleted()) ? "DELETED" : "UNAVAILABLE";
    }

    public void removed(Long id, boolean administrator) {
        jdbc.queryForList("SELECT id FROM `comment` WHERE id=? FOR UPDATE", Long.class, id);
        var ids = new java.util.ArrayList<Long>();
        ids.add(id);
        ids.addAll(comments.selectReplyIdsByRootId(id));
        String reason = administrator ? "ADMIN_REMOVED" : "DELETED";
        for (Long target : ids) {
            // The comment deletion transaction holds its row lock; repeat removal is idempotent.
            if (jdbc.update("UPDATE comment_notification_removal SET reason=?, removed_at=CURRENT_TIMESTAMP WHERE comment_id=?", reason, target) == 0)
                jdbc.update("INSERT INTO comment_notification_removal(comment_id,reason) VALUES(?,?)", target, reason);
        }
    }
}

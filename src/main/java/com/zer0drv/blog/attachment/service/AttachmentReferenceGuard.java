package com.zer0drv.blog.attachment.service;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared DB mutex: content commits / Redis draft writes cannot race a physical deletion. */
@Service
@RequiredArgsConstructor
public class AttachmentReferenceGuard {
    private final JdbcTemplate jdbc;

    public void lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Attachment reference writes require a transaction");
        jdbc.queryForObject("SELECT id FROM attachment_cleanup_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    public void protect(String... values) {
        var references = AttachmentObjectKeys.references(values);
        if (references.isEmpty()) return;
        lock();
        // Locking reads see the latest committed state even under MySQL REPEATABLE READ.
        var blocked = jdbc.queryForList("SELECT object_key FROM attachment_cleanup WHERE state IN ('PURGING','DELETED','MISSING') FOR UPDATE", String.class);
        if (blocked.stream().anyMatch(k -> references.contains(AttachmentObjectKeys.filename(k))))
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "内容引用了已清理或正在清理的附件，请重新上传");
    }
}

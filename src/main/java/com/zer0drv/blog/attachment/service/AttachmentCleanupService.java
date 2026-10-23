package com.zer0drv.blog.attachment.service;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class AttachmentCleanupService {
    private final JdbcTemplate jdbc;
    private final AttachmentReferenceGuard guard;
    private final AttachmentReferenceSnapshot references;
    private final AttachmentObjectStore store;
    private final TransactionTemplate tx;
    private static final Duration OBSERVATION = Duration.ofDays(7);
    private static final Duration MIN_AGE = Duration.ofDays(1);

    public AttachmentCleanupService(JdbcTemplate jdbc, AttachmentReferenceGuard guard,
            AttachmentReferenceSnapshot references, AttachmentObjectStore store, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.guard = guard; this.references = references; this.store = store;
        tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Map<String, Object> scan(long operator) {
        try {
            var inventory = store.inventory(); // Finish both backends before making any marks.
            return tx.execute(status -> {
                guard.lock();
                Set<String> retained = references.load(); // Errors roll back every mark.
                Instant now = Instant.now();
                int marked = 0, protectedCount = 0;
                for (var object : inventory) {
                    var rows = jdbc.queryForList("SELECT * FROM attachment_cleanup WHERE storage=? AND object_key=?", object.storage(), object.key());
                    var existing = rows.isEmpty() ? null : rows.getFirst();
                    if (retained.contains(AttachmentObjectKeys.filename(object.key()))) {
                        protectedCount++;
                        if (existing != null && "OBSERVING".equals(existing.get("state")))
                            jdbc.update("UPDATE attachment_cleanup SET state='RETAINED', last_error='' WHERE id=?", existing.get("id"));
                        continue;
                    }
                    if (object.modified().isAfter(now.minus(MIN_AGE))) continue;
                    if (existing == null) {
                        jdbc.update("INSERT INTO attachment_cleanup(storage,object_key,size_bytes,fingerprint,marked_at,eligible_at,operator_id) VALUES (?,?,?,?,?,?,?)",
                                object.storage(), object.key(), object.size(), object.fingerprint(), Timestamp.from(now), eligible(now), operator);
                        marked++;
                    } else if (Set.of("OBSERVING", "RETAINED").contains(existing.get("state"))
                            && (!object.fingerprint().equals(existing.get("fingerprint")) || "RETAINED".equals(existing.get("state")))) {
                        jdbc.update("UPDATE attachment_cleanup SET state='OBSERVING',size_bytes=?,fingerprint=?,marked_at=?,eligible_at=?,operator_id=?,last_error='' WHERE id=?",
                                object.size(), object.fingerprint(), Timestamp.from(now), eligible(now), operator, existing.get("id"));
                        marked++;
                    }
                }
                return Map.<String, Object>of("scanned", inventory.size(), "marked", marked, "protected", protectedCount,
                        "minAgeDays", 1, "observationDays", 7);
            });
        } catch (Exception failure) { throw unavailable("扫描未完成，未标记任何对象", failure); }
    }

    public PageResult<Map<String, Object>> page(long page, long size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100) throw invalid("分页参数无效");
        var rows = jdbc.query("SELECT * FROM attachment_cleanup ORDER BY id DESC LIMIT ? OFFSET ?", (r, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getLong("id")); row.put("storage", r.getString("storage")); row.put("objectKey", r.getString("object_key"));
            row.put("sizeBytes", r.getLong("size_bytes")); row.put("state", r.getString("state"));
            row.put("markedAt", r.getTimestamp("marked_at").toInstant()); row.put("eligibleAt", r.getTimestamp("eligible_at").toInstant());
            row.put("reclaimedBytes", r.getLong("reclaimed_bytes")); row.put("lastError", r.getString("last_error"));
            row.put("operatorId", r.getLong("operator_id"));
            row.put("canPurge", "PURGING".equals(r.getString("state")) || ("OBSERVING".equals(r.getString("state")) && !r.getTimestamp("eligible_at").toInstant().isAfter(Instant.now())));
            return row;
        }, size, (page - 1) * size);
        return PageResult.of(rows, jdbc.queryForObject("SELECT COUNT(*) FROM attachment_cleanup", Long.class), page, size);
    }

    public Map<String, Object> report() {
        return Map.of("states", jdbc.query("SELECT state, COUNT(*), COALESCE(SUM(size_bytes),0) FROM attachment_cleanup GROUP BY state", (r,n) ->
                        Map.of("state",r.getString(1),"objectCount",r.getLong(2),"sizeBytes",r.getLong(3))),
                "reclaimedBytes", jdbc.queryForObject("SELECT COALESCE(SUM(reclaimed_bytes),0) FROM attachment_cleanup", Long.class),
                "observationDays", 7, "minAgeDays", 1);
    }

    public void releaseRestored(String storage, String key) {
        tx.executeWithoutResult(status -> {
            guard.lock();
            jdbc.update("UPDATE attachment_cleanup SET state='RETAINED' WHERE storage=? AND object_key=? AND state='RESTORED'", storage, key);
        });
    }

    public void restore(long id, long operator) {
        tx.executeWithoutResult(status -> {
            guard.lock();
            var row = require(id);
            if (Set.of("DELETED", "MISSING").contains(row.get("state"))) throw invalid("对象已物理删除，无法恢复");
            try {
                if (store.stat((String) row.get("storage"), (String) row.get("object_key")) == null) throw invalid("对象不存在，无法恢复");
            } catch (BusinessException e) { throw e; }
            catch (Exception e) { throw unavailable("无法检查对象，未恢复", e); }
            jdbc.update("UPDATE attachment_cleanup SET state='RESTORED',operator_id=?,last_error='' WHERE id=?", operator, id);
            // Restore library ownership as well as retaining the object, without changing owner or grouping.
            jdbc.update("UPDATE attachment SET deleted=0 WHERE storage=? AND object_key=?", row.get("storage"), row.get("object_key"));
            jdbc.update("UPDATE attachment SET group_id=NULL WHERE storage=? AND object_key=? AND group_id IS NOT NULL AND group_id NOT IN (SELECT id FROM attachment_group WHERE deleted=0)",
                    row.get("storage"), row.get("object_key"));
        });
    }

    public String purge(long id, long operator) {
        // Persist intent BEFORE deleting. A crash leaves a retryable PURGING row; new references are rejected.
        boolean claimed = Boolean.TRUE.equals(tx.execute(status -> {
            guard.lock();
            var row = require(id);
            String state = (String) row.get("state");
            if (Set.of("DELETED", "MISSING").contains(state)) return false;
            if (!Set.of("OBSERVING", "PURGING").contains(state)) throw invalid("该对象不在清理队列中");
            if (((Timestamp) row.get("eligible_at")).toInstant().isAfter(Instant.now())) throw invalid("观察期尚未结束");
            if (references.load().contains(AttachmentObjectKeys.filename((String) row.get("object_key")))) {
                jdbc.update("UPDATE attachment_cleanup SET state='RETAINED',last_error='' WHERE id=?", id);
                return false;
            }
            jdbc.update("UPDATE attachment_cleanup SET state='PURGING',operator_id=?,last_error='' WHERE id=?", operator, id);
            return true;
        }));
        if (!claimed) return (String) require(id).get("state");
        try {
            return tx.execute(status -> {
                guard.lock();
                var row = require(id);
                if (!"PURGING".equals(row.get("state"))) return (String) row.get("state");
                // Also recheck external SQL changes between claim and removal.
                if (references.load().contains(AttachmentObjectKeys.filename((String) row.get("object_key")))) {
                    jdbc.update("UPDATE attachment_cleanup SET state='RETAINED' WHERE id=?", id);
                    return "RETAINED";
                }
                try {
                    var actual = store.stat((String) row.get("storage"), (String) row.get("object_key"));
                    if (actual != null && (!actual.fingerprint().equals(row.get("fingerprint"))
                            || actual.size() != ((Number) row.get("size_bytes")).longValue())) {
                        Instant now = Instant.now();
                        jdbc.update("UPDATE attachment_cleanup SET state='OBSERVING',fingerprint=?,size_bytes=?,marked_at=?,eligible_at=? WHERE id=?",
                                actual.fingerprint(), actual.size(), Timestamp.from(now), eligible(now), id);
                        return "OBSERVING";
                    }
                    boolean removed = actual != null && store.delete(actual);
                    String endState = removed ? "DELETED" : "MISSING";
                    jdbc.update("UPDATE attachment_cleanup SET state=?,completed_at=?,reclaimed_bytes=?,last_error='' WHERE id=?",
                            endState, Timestamp.from(Instant.now()), removed ? actual.size() : 0L, id);
                    return endState;
                } catch (Exception error) { throw unavailable("物理删除未完成，可重试", error); }
            });
        } catch (RuntimeException error) {
            // Keep errors generic: don't expose storage credentials/endpoints or infrastructure paths.
            jdbc.update("UPDATE attachment_cleanup SET last_error='删除未完成，请检查存储连接后重试' WHERE id=? AND state='PURGING'", id);
            throw error;
        }
    }

    public record Preview(byte[] bytes, String filename, String contentType) {}

    public Preview preview(long id) {
        return tx.execute(status -> {
            guard.lock(); // Serialize with purge; never revive or extend observation.
            var row = require(id);
            if (!Set.of("OBSERVING", "RETAINED", "RESTORED").contains(row.get("state")))
                throw invalid("对象不可预览，请刷新清理列表");
            String key = (String) row.get("object_key");
            try {
                byte[] bytes = store.readPreview((String) row.get("storage"), key);
                String extension = key.substring(key.lastIndexOf('.') + 1);
                String type = switch (extension) {
                    case "png" -> "image/png";
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "gif" -> "image/gif";
                    case "webp" -> "image/webp";
                    default -> "application/octet-stream";
                };
                return new Preview(bytes, AttachmentObjectKeys.filename(key), type);
            } catch (Exception e) { throw unavailable("预览不可用，对象可能已删除或存储连接失败", e); }
        });
    }

    private static Timestamp eligible(Instant marked) {
        // DATETIME stores whole seconds. Round UP so it never shortens seven full days.
        return Timestamp.from(marked.plus(OBSERVATION).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1));
    }

    private Map<String, Object> require(long id) {
        var columns = new org.springframework.jdbc.core.ColumnMapRowMapper();
        var rows = jdbc.query("SELECT * FROM attachment_cleanup WHERE id=?", (result, rowNumber) -> {
            var row = columns.mapRow(result, rowNumber);
            // Connector/J getObject(DATETIME) returns LocalDateTime; H2 returns Timestamp.
            // Explicit JDBC conversion preserves the connection's time zone in both drivers.
            row.put("eligible_at", result.getTimestamp("eligible_at"));
            return row;
        }, id);
        if (rows.isEmpty()) throw invalid("清理记录不存在");
        return rows.getFirst();
    }
    private static BusinessException invalid(String message) { return new BusinessException(StatusCode.PARAM_INVALID.getCode(), message); }
    private static BusinessException unavailable(String message, Exception cause) {
        org.slf4j.LoggerFactory.getLogger(AttachmentCleanupService.class).warn(message, cause);
        return new BusinessException(StatusCode.PARAM_INVALID.getCode(), message);
    }
}

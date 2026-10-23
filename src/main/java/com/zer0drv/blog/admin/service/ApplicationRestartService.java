package com.zer0drv.blog.admin.service;

import com.zer0drv.blog.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ApplicationRestartService {
    private final String directory;
    private final OwnerOperations owners;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String instance = UUID.randomUUID().toString();
    public ApplicationRestartService(@Value("${blog.operations.directory:}") String directory,
            OwnerOperations owners, JdbcTemplate jdbc, ObjectMapper json) {
        this.directory = directory; this.owners = owners; this.jdbc = jdbc; this.json = json;
    }
    public Map<String,Object> capability() {
        boolean available = false;
        try { available = !directory.isBlank() && Files.getLastModifiedTime(Path.of(directory, "heartbeat")).toInstant().isAfter(Instant.now().minusSeconds(15)); }
        catch (Exception ignored) { }
        return Map.of("available", available, "instance", instance, "reason", available ? "" : "本机运维进程未连接；请由服务器管理员按运维文档启动进程，或在维护窗口手动重启后端并验证网关。");
    }
    public record Request(String id, String password, String code, String confirmation) {}
    public Map<String,Object> request(long operator, Request request) {
        if (!"RESTART_BACKEND".equals(request.confirmation())) throw invalid("请确认只重启后端应用");
        String id = checkedId(request.id());
        owners.verifyOwner(operator, request.password(), request.code());
        if (!Boolean.TRUE.equals(capability().get("available"))) throw invalid("运维进程未连接，不能提交重启");
        Path root = Path.of(directory), pending = root.resolve("request");
        try {
            if (Files.exists(root.resolve(id + ".json"))) return status(id);
            try { Files.createDirectory(pending); }
            catch (FileAlreadyExistsException busy) {
                var existing = status(id);
                if (!"UNKNOWN".equals(existing.get("state"))) return existing;
                throw invalid("已有重启请求正在处理，请查询其状态");
            }
            var data = Map.<String,Object>of("id", id, "instance", instance, "operator", operator, "requestedAt", Instant.now().toString());
            jdbc.update("INSERT INTO owner_operation_audit(operator_id,target_id,action,outcome) VALUES(?,0,'RESTART','ACCEPTED')", operator);
            Files.writeString(pending.resolve("request.json"), json.writeValueAsString(data), StandardOpenOption.CREATE_NEW);
            return Map.of("id", id, "state", "QUEUED");
        } catch (java.io.IOException error) { throw invalid("重启请求未确认提交，请检查运维目录并查询状态"); }
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> status(String rawId) {
        String id = checkedId(rawId);
        if (directory.isBlank()) throw invalid("未配置运维目录");
        try {
            Path report = Path.of(directory, id + ".json");
            if (Files.exists(report)) return json.readValue(Files.readString(report), Map.class);
            Path pending = Path.of(directory, "request", "request.json");
            if (Files.exists(pending)) {
                var data = json.readValue(Files.readString(pending), Map.class);
                if (id.equals(data.get("id"))) return Map.of("id", id, "state", "QUEUED");
            }
            return Map.of("id", id, "state", "UNKNOWN");
        } catch (Exception error) { throw invalid("无法读取重启状态，请人工核查"); }
    }
    private String checkedId(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("请求编号无效");
        return value;
    }
    private static BusinessException invalid(String message) { return new BusinessException("40023", message); }
}

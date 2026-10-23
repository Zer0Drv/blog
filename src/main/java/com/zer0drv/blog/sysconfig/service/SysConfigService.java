package com.zer0drv.blog.sysconfig.service;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

/** 启动加载 + 写后失效的内存缓存；重启生效项单独保存本进程启动快照。 */
@Slf4j
@Service
public class SysConfigService {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Map<String, SysConfigDefinition> definitions = new LinkedHashMap<>();
    private volatile Map<String, Object> overrides = Map.of();
    private Map<String, Object> bootValues = Map.of();

    public SysConfigService(JdbcTemplate jdbc, Environment environment, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
        SysConfigDefinition.ALL.forEach(d -> definitions.put(d.key(), d));
    }

    @PostConstruct
    void initialize() {
        // 元数据由代码白名单维护；null value 表示继承部署环境，避免种子覆盖环境变量。
        definitions.values().forEach(d -> jdbc.update("""
                INSERT IGNORE INTO sys_config(config_key,config_value,value_type,group_name,description,validation_rule,hot_reload)
                VALUES (?,NULL,?,?,?,?,?)""", d.key(), d.type(), d.group(), d.description(), encode(rule(d)), d.hotReload()));
        refresh();
        Map<String, Object> boot = new LinkedHashMap<>();
        definitions.values().forEach(d -> {
            Object value = configured(d, overrides);
            validateRuntimePrerequisites(d, value);
            boot.put(d.key(), value);
        });
        bootValues = Map.copyOf(boot);
    }

    private Map<String, Object> rule(SysConfigDefinition d) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("min", d.min()); rule.put("max", d.max()); rule.put("regex", d.regex()); rule.put("options", d.options());
        return rule;
    }

    private SysConfigDefinition definition(String key) {
        var definition = definitions.get(key);
        if (definition == null) throw invalid("不支持的系统参数");
        return definition;
    }

    public boolean contains(String key) { return definitions.containsKey(key); }
    public long getLong(String key) { return ((Number) value(key)).longValue(); }
    public boolean getBool(String key) { return (Boolean) value(key); }
    public String getString(String key) { return (String) value(key); }
    @SuppressWarnings("unchecked")
    public List<String> getList(String key) { return (List<String>) value(key); }

    private Object value(String key) {
        var d = definition(key);
        return d.hotReload() ? configured(d, overrides) : bootValues.get(key);
    }

    private Object configured(SysConfigDefinition d, Map<String, Object> snapshot) {
        return snapshot.containsKey(d.key()) ? snapshot.get(d.key()) : fallback(d);
    }

    private String fallbackSource(SysConfigDefinition d) {
        if (environment.getProperty(d.env()) != null) return "ENVIRONMENT";
        if (environment.getProperty(d.key()) != null || d.key().equals("blog.upload.storage")) return "YAML";
        return "DEFAULT";
    }

    private Object fallback(SysConfigDefinition d) {
        String raw = environment.getProperty(d.env());
        if (raw == null) raw = environment.getProperty(d.key());
        if (raw == null && d.key().equals("blog.upload.storage"))
            raw = environment.getProperty("blog.minio.enabled", Boolean.class, false) ? "MINIO" : "LOCAL";
        if (raw == null) return d.defaultValue();
        try {
            Object value = d.type().equals("STRING") ? raw : json.readValue(raw, Object.class);
            if (d.key().equals("jwt.expiration-ms") && value instanceof Number number) value = Math.min(900000L, number.longValue());
            return normalize(d, value);
        } catch (Exception e) { throw invalid(d.key() + " 的环境或 YAML 配置无效"); }
    }

    public List<Map<String, Object>> list() {
        Map<String, Object> snapshot = overrides;
        return definitions.values().stream().map(d -> {
            Map<String, Object> view = new LinkedHashMap<>();
            Object configured = configured(d, snapshot);
            Object active = d.hotReload() ? configured : bootValues.get(d.key());
            view.put("key", d.key()); view.put("type", d.type()); view.put("group", d.group());
            view.put("description", d.description()); view.put("value", configured); view.put("activeValue", active);
            view.put("defaultValue", d.defaultValue()); view.put("fallbackValue", fallback(d));
            view.put("source", snapshot.containsKey(d.key()) ? "DATABASE" : fallbackSource(d));
            view.put("hotReload", d.hotReload()); view.put("restartRequired", !Objects.equals(configured, active));
            view.put("validation", rule(d));
            return view;
        }).toList();
    }

    public synchronized void update(Map<String, Object> requested, Long operator) {
        if (requested == null || requested.isEmpty() || requested.size() > definitions.size()) throw invalid("参数不能为空或超过白名单数量");
        Map<String, Object> normalized = new LinkedHashMap<>();
        requested.forEach((key, value) -> normalized.put(key, normalize(definition(key), value)));
        mutate(normalized, operator, false);
    }

    /** 删除数据库覆盖，恢复到 环境变量 > YAML > 内置默认值。 */
    public synchronized void reset(String key, Long operator) {
        definition(key);
        mutate(Collections.singletonMap(key, null), operator, true);
    }

    private void mutate(Map<String, Object> values, Long operator, boolean reset) {
        Map<String, Object> committed = transaction.execute(status -> {
            // 所有实例按同一顺序锁住已知配置行，审计旧值来自数据库，不信任本机缓存。
            Map<String, Object> current = readOverrides(true);
            for (var entry : values.entrySet()) {
                var d = definition(entry.getKey());
                Object next = reset ? fallback(d) : entry.getValue();
                validateRuntimePrerequisites(d, next);
                String beforeSource = current.containsKey(d.key()) ? "DATABASE" : fallbackSource(d);
                jdbc.update("""
                        INSERT INTO sys_config_audit(config_key,action,old_value,new_value,old_source,new_source,operator_id)
                        VALUES (?,?,?,?,?,?,?)""", d.key(), reset ? "RESET" : "SET", encode(configured(d, current)), encode(next),
                        beforeSource, reset ? fallbackSource(d) : "DATABASE", operator);
                jdbc.update("UPDATE sys_config SET config_value=?,update_time=? WHERE config_key=?",
                        reset ? null : encode(next), LocalDateTime.now(), d.key());
                if (reset) current.remove(d.key());
                else current.put(d.key(), next);
            }
            return Map.copyOf(current);
        });
        // TransactionTemplate 返回时已经提交；失败不发布未提交的缓存内容。
        overrides = Objects.requireNonNull(committed);
    }

    private void validateRuntimePrerequisites(SysConfigDefinition d, Object value) {
        if (d.key().equals("blog.upload.storage") && value.equals("MINIO")
                && !environment.getProperty("blog.minio.enabled", Boolean.class, false))
            throw invalid("请先通过环境配置启用 MinIO 客户端并重启，再选择 MINIO 存储");
        if (d.key().equals("blog.auth.cookie.same-site") && value.equals("None")
                && !environment.getProperty("blog.auth.cookie.secure", Boolean.class, true))
            throw invalid("SameSite=None 要求 AUTH_COOKIE_SECURE=true");
    }

    private Object normalize(SysConfigDefinition d, Object value) {
        if (value == null) throw invalid(d.key() + " 不能为空；请使用重置操作恢复继承");
        switch (d.type()) {
            case "INT" -> {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()
                        || n.longValue() < d.min() || n.longValue() > d.max()) throw invalid(d.key() + " 必须为允许范围内的整数");
                return ((Number) value).longValue();
            }
            case "BOOL" -> {
                if (value instanceof Boolean) return value;
            }
            case "STRING" -> {
                if (value instanceof String text && text.matches(d.regex())) return text;
            }
            case "JSON" -> {
                if (value instanceof List<?> list && !list.isEmpty() && list.size() <= d.options().size()
                        && list.stream().allMatch(item -> item instanceof String && d.options().contains(item))
                        && new HashSet<>(list).size() == list.size()) return List.copyOf(list);
            }
        }
        throw invalid(d.key() + " 的值类型或格式不正确");
    }

    private Map<String, Object> readOverrides(boolean lock) {
        Map<String, Object> result = new LinkedHashMap<>();
        jdbc.query("SELECT config_key,config_value FROM sys_config ORDER BY config_key" + (lock ? " FOR UPDATE" : ""), rs -> {
            String key = rs.getString(1), value = rs.getString(2);
            if (definitions.containsKey(key) && value != null) {
                try { result.put(key, normalize(definition(key), json.readValue(value, Object.class))); }
                catch (Exception e) { throw invalid(key + " 的数据库配置无效"); }
            }
        });
        return result;
    }

    public synchronized void refresh() { overrides = Map.copyOf(readOverrides(false)); }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refreshOtherInstances() {
        try { refresh(); } catch (Exception e) { log.warn("系统参数缓存刷新失败，保留上次有效快照"); }
    }

    public PageResult<Map<String, Object>> audit(long page, long size) {
        long actualPage = Math.max(1, Math.min(page, 100000)), actualSize = Math.max(1, Math.min(size, 100));
        var records = jdbc.query("SELECT id,config_key,action,old_value,new_value,old_source,new_source,operator_id,create_time FROM sys_config_audit ORDER BY id DESC LIMIT ? OFFSET ?", (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(rs.getLong("id"))); item.put("key", rs.getString("config_key"));
            item.put("action", rs.getString("action")); item.put("oldValue", json.readValue(rs.getString("old_value"), Object.class));
            item.put("newValue", json.readValue(rs.getString("new_value"), Object.class)); item.put("oldSource", rs.getString("old_source"));
            item.put("newSource", rs.getString("new_source")); item.put("operatorId", String.valueOf(rs.getLong("operator_id")));
            item.put("createdAt", rs.getTimestamp("create_time").toLocalDateTime().toString());
            return item;
        }, actualSize, (actualPage - 1) * actualSize);
        return PageResult.of(records, jdbc.queryForObject("SELECT COUNT(*) FROM sys_config_audit", Long.class), actualPage, actualSize);
    }

    private String encode(Object value) { return json.writeValueAsString(value); }
    private BusinessException invalid(String message) { return new BusinessException(StatusCode.PARAM_INVALID.getCode(), message); }
}

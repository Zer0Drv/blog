package com.zer0drv.blog.article.transfer;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * YAML front matter 解析 / 渲染（issue #16）：Jekyll/Halo/WordPress 风格的
 * `---` 包围 YAML 头。解析复用 spring-boot-starter 传递引入的 snakeyaml，
 * SafeConstructor 只允许标准 YAML 类型（Map/List/标量），不构造任意 Java 对象。
 * 渲染侧值只放 String / List&lt;String&gt;，由 snakeyaml 负责转义与引号。
 *
 * @author Yoruhaki
 */
public final class FrontMatterParser {

    /**
     * 起始 / 结束分隔线（整行 trim 后等于该值）
     */
    private static final String DELIMITER = "---";

    /**
     * YAML 文档结束符，同样可作为 front matter 结束行
     */
    private static final String DOC_END = "...";

    private FrontMatterParser() {
    }

    /**
     * 解析结果：meta 为元数据键值（键一律 String），body 为剥离 front matter 后的正文。
     */
    public record FrontMatter(Map<String, Object> meta, String body) {
    }

    /**
     * 解析内容开头的 front matter。
     *
     * @return null 表示无 front matter（首行不是 `---`，或没有结束行——此时内容原样保留）；
     * 有 front matter 时返回剥离后的正文与元数据
     * @throws BusinessException 40023：分隔线完整但 YAML 非法，或 YAML 不是键值对
     */
    public static FrontMatter parse(String content) {
        if (Objects.isNull(content)) {
            return null;
        }
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !DELIMITER.equals(stripCr(lines[0]).trim())) {
            return null;
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = stripCr(lines[i]).trim();
            if (DELIMITER.equals(line) || DOC_END.equals(line)) {
                end = i;
                break;
            }
        }
        // 没有结束行：按无 front matter 处理（可能只是正文开头的水平线）
        if (end < 0) {
            return null;
        }
        String yamlText = String.join("\n", List.of(lines).subList(1, end));
        Map<String, Object> meta = loadMeta(yamlText);
        String body = String.join("\n", List.of(lines).subList(end + 1, lines.length));
        // front matter 与正文之间按惯例有一个空行，剥离时去掉首个空行（兼容 CRLF），其余原样保留
        if (body.startsWith("\r\n")) {
            body = body.substring(2);
        } else if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        return new FrontMatter(meta, body);
    }

    /**
     * 渲染 front matter 块：`---\n<yaml>---\n\n`。meta 为空时仍输出空块（导出格式稳定）。
     * 调用方应使用 LinkedHashMap 控制字段顺序。
     */
    public static String render(Map<String, Object> meta) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // 中文等非 ASCII 字符原样输出，不转义为 unicode 转义序列
        options.setAllowUnicode(true);
        // 导出值域只有 String / List<String>，默认 Representer 即可（snakeyaml 2.x 无
        // Yaml(BaseConstructor, DumperOptions) 构造器，dump 侧用 Representer 重载）
        Yaml yaml = new Yaml(new Representer(options), options);
        String dump = Objects.isNull(meta) || meta.isEmpty() ? "" : yaml.dump(meta);
        return DELIMITER + "\n" + dump + DELIMITER + "\n\n";
    }

    /**
     * 解析 YAML 文本为 String 键 Map；空块 → 空 Map
     */
    private static Map<String, Object> loadMeta(String yamlText) {
        if (yamlText.isBlank()) {
            return Map.of();
        }
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        } catch (Exception e) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                    "front matter 解析失败：" + e.getMessage());
        }
        if (Objects.isNull(loaded)) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                    "front matter 必须是键值对（YAML mapping）");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        map.forEach((key, value) -> meta.put(String.valueOf(key), value));
        return meta;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}

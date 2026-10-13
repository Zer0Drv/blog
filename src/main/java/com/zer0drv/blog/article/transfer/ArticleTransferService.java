package com.zer0drv.blog.article.transfer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zer0drv.blog.article.api.ArticleStatus;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleTag;
import com.zer0drv.blog.article.domain.ArticleVisibility;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.enums.EditorType;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.article.mapper.ArticleTagMapper;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.vo.ArticleExportVO;
import com.zer0drv.blog.article.vo.ArticleImportResultVO;
import com.zer0drv.blog.category.domain.Category;
import com.zer0drv.blog.category.mapper.CategoryMapper;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import com.zer0drv.blog.tag.domain.Tag;
import com.zer0drv.blog.tag.mapper.TagMapper;
import com.zer0drv.blog.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 文章 Markdown 导入导出（issue #16，对标 Halo / WordPress 导入导出插件）。
 *
 * <p>导出：单篇 .md/.html（正文原文 + YAML front matter：标题/发布时间/更新时间/状态/
 * 标签/分类/摘要/封面）；批量勾选打包 zip（作者只能导自己的，ADMIN 全部）。
 *
 * <p>导入：.md/.markdown/.txt/.html 单文件或 .zip 批量；front matter 白名单字段
 * （title/date/tags/category/summary/cover）自动填充元数据，其余字段忽略并记入警告；
 * 一律创建 DRAFT 草稿（status 字段明确不应用）；标签按名匹配、缺失自动创建
 * （标签写权限本就属 ADMIN/AUTHOR），分类按名匹配但不自动创建（分类管理仅 ADMIN）；
 * 外链图片保留原样并把清单随响应返回（待转存清单，不在导入链路远程拉取）。
 *
 * <p>归属 article 模块内部服务：文章创建复用 {@link ArticleService#create}（含
 * RICHTEXT 的 jsoup 清洗与 content_text 生成），跨模块仅按现有先例直读
 * tag / category 的 Mapper（ArchUnit 红线只管 article / interaction→comment 方向）。
 *
 * @author Yoruhaki
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleTransferService {

    /**
     * 单文件大小上限：2MB（zip 内单条同此限）
     */
    private static final long IMPORT_MAX_SIZE = 2L * 1024 * 1024;

    /**
     * zip 包大小上限：5MB（与 spring.servlet.multipart.max-file-size 对齐，超出由 multipart 层先拒）
     */
    private static final long IMPORT_ZIP_MAX_SIZE = 5L * 1024 * 1024;

    /**
     * zip 内文件数量上限
     */
    private static final int IMPORT_ZIP_MAX_ENTRIES = 50;

    /**
     * zip 解压后总字节上限：20MB（防 zip bomb）
     */
    private static final long IMPORT_ZIP_MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    /**
     * 批量导出文章数上限
     */
    private static final int EXPORT_ZIP_MAX_ARTICLES = 200;

    /**
     * 导入标题上限：与 article.title / ArticleSaveDTO 的字段约束一致
     */
    private static final int IMPORT_TITLE_MAX_LENGTH = 200;

    /**
     * 摘要 / 封面 / 标签名长度上限：与表结构（summary 500、cover 512、tag.name 64）一致
     */
    private static final int SUMMARY_MAX_LENGTH = 500;
    private static final int COVER_MAX_LENGTH = 512;
    private static final int TAG_NAME_MAX_LENGTH = 64;

    /**
     * 导入文件扩展名 → 编辑器类型：.md/.markdown/.txt → MARKDOWN；.html/.htm → RICHTEXT
     */
    private static final Map<String, String> IMPORT_EDITOR_TYPES = Map.of(
            "md", EditorType.MARKDOWN.name(),
            "markdown", EditorType.MARKDOWN.name(),
            "txt", EditorType.MARKDOWN.name(),
            "html", EditorType.RICHTEXT.name(),
            "htm", EditorType.RICHTEXT.name());

    /**
     * Markdown 一级标题行（# 标题，排除 ## 及以上），无 front matter 标题时取作文章标题
     */
    private static final Pattern H1_PATTERN = Pattern.compile("^#\\s+(\\S.*)$");

    /**
     * front matter 应用白名单：仅这些字段会被写入草稿元数据
     */
    private static final Set<String> META_APPLIED_KEYS =
            Set.of("title", "date", "tags", "category", "summary", "cover");

    /**
     * 已知但静默忽略的字段：updated 由系统维护（导出会写入，导入不回填，也不打扰用户）
     */
    private static final Set<String> META_SILENT_IGNORED_KEYS = Set.of("updated");

    /**
     * Markdown 图片语法中的外链 URL
     */
    private static final Pattern MD_IMAGE_URL =
            Pattern.compile("!\\[[^\\]]*\\]\\(\\s*(https?://[^)\\s\"']+)");

    /**
     * HTML img 标签中的外链 URL
     */
    private static final Pattern HTML_IMAGE_URL =
            Pattern.compile("<img[^>]+?src=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * zip 条目文件名非法字符（Windows / *nix 通用子集）与控制字符
     */
    private static final Pattern ILLEGAL_FILE_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final DateTimeFormatter PLAIN_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ArticleService articleService;
    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final CategoryMapper categoryMapper;

    /* ================================ 导出 ================================ */

    /**
     * 导出单篇文章为文件（md / html）。可见性与文章详情完全一致（已发布=公开；
     * 草稿/下架=本人或 ADMIN，其余一律 ARTICLE_NOT_EXIST，不暴露存在性）。
     * format 可空：缺省按文章 editorType（MARKDOWN→md，RICHTEXT→html）；
     * 内容为 YAML front matter + 正文原文，不做 Markdown 渲染转换。
     */
    public ArticleExportVO exportArticle(Long id, String format, Jwt jwt) {
        Article article = articleMapper.selectById(id);
        if (Objects.isNull(article)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        if (!ArticleVisibility.isVisible(article) && !isAuthorOrAdmin(article, jwt)) {
            throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
        }
        String extension = resolveExportExtension(article, format);
        String title = Objects.isNull(article.getTitle()) ? "" : article.getTitle();
        String fileName = title.isBlank() ? "article-" + article.getId() : title;
        return new ArticleExportVO(renderExport(article).getBytes(StandardCharsets.UTF_8), fileName, extension);
    }

    /**
     * 批量导出打包 zip（ADMIN / AUTHOR）：条目为各文章默认格式文件（front matter + 正文）。
     * 权限沿用文章操作鉴权：AUTHOR 只能导自己的，ADMIN 全部；对 AUTHOR 而言
     * 他人文章与不存在同语义（ARTICLE_NOT_EXIST，不暴露存在性）。
     */
    public ArticleExportVO exportArticlesZip(List<Long> ids, Jwt jwt) {
        if (Objects.isNull(ids) || ids.isEmpty()) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "请选择要导出的文章");
        }
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty() || distinctIds.size() > EXPORT_ZIP_MAX_ARTICLES) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                    "单次最多导出 " + EXPORT_ZIP_MAX_ARTICLES + " 篇");
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        boolean admin = isAdmin();
        Map<Long, Article> byId = articleMapper.selectByIds(distinctIds).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));
        List<Article> articles = new ArrayList<>();
        for (Long id : distinctIds) {
            Article article = byId.get(id);
            // 他人文章（对 AUTHOR）与不存在同语义，不暴露存在性
            if (Objects.isNull(article) || (!admin && !userId.equals(article.getAuthorId()))) {
                throw new BusinessException(StatusCode.ARTICLE_NOT_EXIST);
            }
            articles.add(article);
        }
        return new ArticleExportVO(buildZip(articles), "articles-export", "zip");
    }

    /**
     * front matter + 正文原文。字段顺序固定（LinkedHashMap），空值字段不输出。
     */
    private String renderExport(Article article) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", Objects.isNull(article.getTitle()) ? "" : article.getTitle());
        LocalDateTime date = Objects.nonNull(article.getPublishTime())
                ? article.getPublishTime()
                : (Objects.nonNull(article.getCreateTime()) ? article.getCreateTime() : LocalDateTime.now());
        meta.put("date", date.format(ISO));
        if (Objects.nonNull(article.getUpdateTime())) {
            meta.put("updated", article.getUpdateTime().format(ISO));
        }
        meta.put("status", article.getStatus());
        List<String> tagNames = tagNamesOf(article.getId());
        if (!tagNames.isEmpty()) {
            meta.put("tags", tagNames);
        }
        if (Objects.nonNull(article.getCategoryId())) {
            String categoryName = categoryNameOf(article.getCategoryId());
            if (Objects.nonNull(categoryName)) {
                meta.put("category", categoryName);
            }
        }
        if (Objects.nonNull(article.getSummary()) && !article.getSummary().isBlank()) {
            meta.put("summary", article.getSummary());
        }
        if (Objects.nonNull(article.getCover()) && !article.getCover().isBlank()) {
            meta.put("cover", article.getCover());
        }
        return FrontMatterParser.render(meta)
                + (Objects.isNull(article.getContent()) ? "" : article.getContent());
    }

    /**
     * 打 zip：文件名净化（路径分隔与非法字符→_），同名冲突追加 -<id> 保证条目唯一
     */
    private byte[] buildZip(List<Article> articles) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            Set<String> usedNames = new HashSet<>();
            for (Article article : articles) {
                String extension = EditorType.RICHTEXT.name().equals(article.getEditorType()) ? "html" : "md";
                String baseName = sanitizeFileName(
                        Objects.isNull(article.getTitle()) ? "" : article.getTitle());
                if (baseName.isBlank()) {
                    baseName = "article-" + article.getId();
                }
                String entryName = baseName + "." + extension;
                if (!usedNames.add(entryName)) {
                    entryName = baseName + "-" + article.getId() + "." + extension;
                    usedNames.add(entryName);
                }
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(renderExport(article).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            // ByteArrayOutputStream 不会抛 IO 异常，兜底转 500
            throw new BusinessException(StatusCode.FAIL.getCode(), "导出打包失败");
        }
        return baos.toByteArray();
    }

    private static String sanitizeFileName(String name) {
        return ILLEGAL_FILE_NAME_CHARS.matcher(name).replaceAll("_").trim();
    }

    /**
     * 导出扩展名：format 省略时按 editorType（MARKDOWN→md，RICHTEXT→html）；
     * 显式指定仅允许 md / html
     */
    private String resolveExportExtension(Article article, String format) {
        if (Objects.isNull(format) || format.isBlank()) {
            return EditorType.RICHTEXT.name().equals(article.getEditorType()) ? "html" : "md";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (!"md".equals(normalized) && !"html".equals(normalized)) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "导出格式仅支持 md / html");
        }
        return normalized;
    }

    private List<String> tagNamesOf(Long articleId) {
        List<ArticleTag> relations = articleTagMapper.selectList(Wrappers.lambdaQuery(ArticleTag.class)
                .eq(ArticleTag::getArticleId, articleId));
        if (relations.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = relations.stream().map(ArticleTag::getTagId).toList();
        return tagMapper.selectByIds(tagIds).stream().map(Tag::getName).toList();
    }

    private String categoryNameOf(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        return Objects.isNull(category) ? null : category.getName();
    }

    /* ================================ 导入 ================================ */

    /**
     * 导入入口（ADMIN / AUTHOR）：.md/.markdown/.txt/.html 单文件（≤2MB）或 .zip（≤5MB，
     * ≤50 个文件）。单文件硬性失败抛业务异常；zip 逐条容错，失败进 failed 明细。
     * 响应统一为成功草稿 id 清单（含警告与外链图片待转存清单）+ 失败明细。
     */
    public ArticleImportResultVO importArticles(MultipartFile file, Jwt jwt) {
        if (Objects.isNull(file) || file.isEmpty()) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "导入文件不能为空");
        }
        String originalFilename = Objects.isNull(file.getOriginalFilename()) ? "" : file.getOriginalFilename();
        if ("zip".equals(extensionOf(originalFilename))) {
            if (file.getSize() > IMPORT_ZIP_MAX_SIZE) {
                throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "zip 文件过大，最大 5MB");
            }
            return importZip(readBytes(file), jwt);
        }
        ArticleImportResultVO.ImportedItem item = importOne(originalFilename, readBytes(file), jwt);
        return new ArticleImportResultVO(List.of(item), List.of());
    }

    /**
     * zip 批量导入：先整包校验（条目数 / 解压总量 / Zip Slip 路径穿越），再逐条导入。
     * 整包问题（损坏 / 超限 / 路径穿越 / 无可导入文件）抛 40023；单条问题进 failed。
     */
    private ArticleImportResultVO importZip(byte[] zipBytes, Jwt jwt) {
        List<ZipItem> items = unzip(zipBytes);
        List<ArticleImportResultVO.ImportedItem> imported = new ArrayList<>();
        List<ArticleImportResultVO.FailedItem> failed = new ArrayList<>();
        for (ZipItem item : items) {
            String entryName = item.name();
            if (!IMPORT_EDITOR_TYPES.containsKey(extensionOf(entryName))) {
                failed.add(new ArticleImportResultVO.FailedItem(entryName,
                        "仅支持 .md/.markdown/.txt/.html 文件"));
                continue;
            }
            try {
                imported.add(importOne(entryName, item.bytes(), jwt));
            } catch (BusinessException e) {
                failed.add(new ArticleImportResultVO.FailedItem(entryName, e.getMessage()));
            }
        }
        return new ArticleImportResultVO(List.copyOf(imported), List.copyOf(failed));
    }

    /**
     * 解开 zip 到内存（zip ≤5MB，逐条 ≤2MB，总量 ≤20MB）：
     * Zip Slip 防御——条目名含 ".." 段或为绝对路径时整包拒绝（虽不落盘，仍做路径穿越校验）；
     * 目录条目跳过；空 zip 报「没有可导入的文件」。
     */
    private List<ZipItem> unzip(byte[] zipBytes) {
        List<ZipItem> items = new ArrayList<>();
        long totalBytes = 0;
        int fileEntries = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while (Objects.nonNull(entry = zis.getNextEntry())) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (isTraversalPath(name)) {
                    throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                            "zip 包含非法路径条目：" + name);
                }
                fileEntries++;
                if (fileEntries > IMPORT_ZIP_MAX_ENTRIES) {
                    throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                            "zip 内文件数量过多，最多 " + IMPORT_ZIP_MAX_ENTRIES + " 个");
                }
                byte[] bytes = readEntryBytes(zis);
                totalBytes += bytes.length;
                if (totalBytes > IMPORT_ZIP_MAX_TOTAL_BYTES) {
                    throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                            "zip 解压后总量过大");
                }
                items.add(new ZipItem(name, bytes));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // ZipException 等：包损坏或非 zip 内容
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "zip 文件损坏或无法读取");
        }
        if (items.isEmpty()) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "zip 中没有可导入的文件");
        }
        return items;
    }

    /**
     * 单条读取上限 IMPORT_MAX_SIZE + 1（多读一字节用于判定超限，避免截断静默入库）
     */
    private static byte[] readEntryBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zis.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > IMPORT_MAX_SIZE) {
                // 超限条目：返回标记字节，由 importOne 按「文件过大」统一口径进 failed
                return buffer.toByteArray();
            }
        }
        return buffer.toByteArray();
    }

    /**
     * 路径穿越校验：按 / 与 \ 切段，任何一段为 ".." 或以 / \ 开头或含盘符即非法
     */
    private static boolean isTraversalPath(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[a-zA-Z]:/.*")) {
            return true;
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private record ZipItem(String name, byte[] bytes) {
    }

    /**
     * 导入单个文件内容并创建为当前用户的 DRAFT 草稿。front matter 白名单字段
     * 自动填充（title/date/tags/category/summary/cover）；title 缺省时 Markdown
     * 取首个一级标题（该行从正文移除），否则用文件名；外链图片保留并计入清单。
     */
    private ArticleImportResultVO.ImportedItem importOne(String filename, byte[] bytes, Jwt jwt) {
        if (bytes.length == 0) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "导入文件不能为空");
        }
        if (bytes.length > IMPORT_MAX_SIZE) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "文件过大，最大 2MB");
        }
        String editorType = IMPORT_EDITOR_TYPES.get(extensionOf(filename));
        if (Objects.isNull(editorType)) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(),
                    "仅支持 .md/.markdown/.txt/.html 文件");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        // UTF-8 BOM 剥离，避免首行 "---" / "# 标题" 因 BOM 失配
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Object> meta = Map.of();
        FrontMatterParser.FrontMatter frontMatter = FrontMatterParser.parse(content);
        if (Objects.nonNull(frontMatter)) {
            meta = frontMatter.meta();
            content = frontMatter.body();
        }

        // 标题：front matter title 优先；否则 Markdown 取首个 H1（该行从正文移除）；
        // 再退文件名去扩展名。front matter 已有标题时不动正文 H1，保证导出→导入 round-trip 保真
        String title = metaScalar(meta, "title", warnings);
        if (Objects.nonNull(title) && title.isBlank()) {
            title = null;
        }
        if (Objects.isNull(title) && EditorType.MARKDOWN.name().equals(editorType)) {
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                var matcher = H1_PATTERN.matcher(stripCr(lines[i]));
                if (matcher.matches()) {
                    title = matcher.group(1).trim();
                    lines[i] = null;
                    break;
                }
            }
            content = Arrays.stream(lines).filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        }
        if (Objects.isNull(title) || title.isBlank()) {
            title = baseNameOf(filename);
        }
        if (title.isBlank()) {
            title = "未命名文章";
        }
        if (title.length() > IMPORT_TITLE_MAX_LENGTH) {
            title = title.substring(0, IMPORT_TITLE_MAX_LENGTH);
        }

        ArticleSaveDTO dto = new ArticleSaveDTO();
        dto.setTitle(title);
        dto.setContent(content);
        dto.setEditorType(editorType);
        // 导入一律创建草稿，不允许经 front matter 直接发布
        dto.setStatus(ArticleStatus.DRAFT.name());

        applyMeta(meta, dto, warnings);
        warnIgnoredMeta(meta, warnings);

        // 复用创建逻辑（RICHTEXT 随之走 jsoup 白名单清洗），固定落 DRAFT 草稿
        Long articleId = articleService.create(dto, jwt);

        // date → publish_time：保留原文发布时间（发布时沿用），解析失败已记 warning
        LocalDateTime date = metaDateTime(meta.get("date"), warnings);
        if (Objects.nonNull(date)) {
            Article article = articleMapper.selectById(articleId);
            if (Objects.nonNull(article)) {
                article.setPublishTime(date.withNano(0));
                articleMapper.updateById(article);
            }
        }
        return new ArticleImportResultVO.ImportedItem(articleId, title, List.copyOf(warnings),
                extractExternalImages(content));
    }

    /**
     * 应用 front matter 白名单字段到保存 DTO（标签按名匹配 / 自动创建，分类按名匹配不自动创建）
     */
    private void applyMeta(Map<String, Object> meta, ArticleSaveDTO dto, List<String> warnings) {
        String summary = metaScalar(meta, "summary", warnings);
        if (Objects.nonNull(summary) && !summary.isBlank()) {
            dto.setSummary(summary.length() <= SUMMARY_MAX_LENGTH
                    ? summary : summary.substring(0, SUMMARY_MAX_LENGTH));
        }
        String cover = metaScalar(meta, "cover", warnings);
        if (Objects.nonNull(cover) && !cover.isBlank()) {
            if (cover.length() > COVER_MAX_LENGTH) {
                warnings.add("封面 URL 过长，已忽略");
            } else {
                dto.setCover(cover);
            }
        }
        String categoryName = metaScalar(meta, "category", warnings);
        if (Objects.nonNull(categoryName) && !categoryName.isBlank()) {
            // 分类名不唯一（表无唯一约束），取第一个匹配；分类管理仅 ADMIN，导入不自动创建
            List<Category> categories = categoryMapper.selectList(Wrappers.lambdaQuery(Category.class)
                    .eq(Category::getName, categoryName.trim()).last("LIMIT 1"));
            if (categories.isEmpty()) {
                warnings.add("分类「" + categoryName.trim() + "」不存在，已跳过");
            } else {
                dto.setCategoryId(categories.get(0).getId());
            }
        }
        List<String> tagNames = metaStringList(meta.get("tags"));
        if (!tagNames.isEmpty()) {
            dto.setTagIds(resolveTagIds(tagNames, warnings));
        }
    }

    /**
     * 被忽略字段的警告：status 明确提示（导入一律草稿）；其余未知字段汇总一条；
     * updated 属已知字段静默忽略（导出会写，导入不回填）
     */
    private void warnIgnoredMeta(Map<String, Object> meta, List<String> warnings) {
        if (meta.containsKey("status")) {
            warnings.add("front matter 的 status 字段已忽略：导入一律创建草稿");
        }
        List<String> unknown = meta.keySet().stream()
                .filter(key -> !META_APPLIED_KEYS.contains(key)
                        && !META_SILENT_IGNORED_KEYS.contains(key)
                        && !"status".equals(key))
                .toList();
        if (!unknown.isEmpty()) {
            warnings.add("已忽略未支持的 front matter 字段：" + String.join("、", unknown));
        }
    }

    /**
     * 标签名 → id：按名精确匹配，缺失自动创建（uk_name 并发冲突时重查）；
     * 空名跳过，超长（>64）跳过并记 warning
     */
    private List<Long> resolveTagIds(List<String> names, List<String> warnings) {
        List<Long> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : names) {
            String name = Objects.isNull(raw) ? "" : raw.trim();
            if (name.isEmpty() || !seen.add(name)) {
                continue;
            }
            if (name.length() > TAG_NAME_MAX_LENGTH) {
                warnings.add("标签「" + name.substring(0, TAG_NAME_MAX_LENGTH) + "…」名称过长，已跳过");
                continue;
            }
            Tag tag = tagMapper.selectOne(Wrappers.lambdaQuery(Tag.class).eq(Tag::getName, name));
            if (Objects.isNull(tag)) {
                try {
                    tag = new Tag();
                    tag.setName(name);
                    tagMapper.insert(tag);
                } catch (DuplicateKeyException e) {
                    // 并发导入同名标签：唯一键冲突后重查
                    tag = tagMapper.selectOne(Wrappers.lambdaQuery(Tag.class).eq(Tag::getName, name));
                }
            }
            if (Objects.nonNull(tag)) {
                ids.add(tag.getId());
            }
        }
        return ids;
    }

    /**
     * 提取正文中的外链图片 URL（Markdown 图片语法 + HTML img 标签），去重保序。
     * 导入保留外链原样，该清单即「待转存附件库」清单随响应返回。
     */
    private List<String> extractExternalImages(String content) {
        if (Objects.isNull(content) || content.isBlank()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        var mdMatcher = MD_IMAGE_URL.matcher(content);
        while (mdMatcher.find()) {
            urls.add(mdMatcher.group(1));
        }
        var htmlMatcher = HTML_IMAGE_URL.matcher(content);
        while (htmlMatcher.find()) {
            urls.add(htmlMatcher.group(1));
        }
        return List.copyOf(urls);
    }

    /**
     * 读取 front matter 标量字段：仅接受 String / Number / Boolean；
     * 复杂类型（List / Map）记 warning 并忽略
     */
    private String metaScalar(Map<String, Object> meta, String key, List<String> warnings) {
        Object value = meta.get(key);
        if (Objects.isNull(value)) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        warnings.add("front matter 字段「" + key + "」类型不支持，已忽略");
        return null;
    }

    /**
     * 读取 front matter 字符串列表字段：YAML 列表逐项取标量；字符串按逗号分隔
     * （兼容 tags: a, b 写法）
     */
    private List<String> metaStringList(Object value) {
        if (Objects.isNull(value)) {
            return List.of();
        }
        if (value instanceof String string) {
            return List.of(string.split("[,，]", -1));
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull)
                    .map(item -> (item instanceof String || item instanceof Number || item instanceof Boolean)
                            ? String.valueOf(item) : null)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return List.of();
    }

    /**
     * 解析 front matter 的 date：支持 ISO 日期时间、ISO 日期、yyyy-MM-dd HH:mm:ss，
     * 以及 snakeyaml 对未加引号时间戳构造的 java.util.Date；失败记 warning 返回 null
     */
    private LocalDateTime metaDateTime(Object value, List<String> warnings) {
        if (Objects.isNull(value)) {
            return null;
        }
        if (value instanceof Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).withNano(0);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof LocalDate localDate) {
            return localDate.atStartOfDay();
        }
        if (value instanceof String string && !string.isBlank()) {
            String text = string.trim();
            for (DateTimeFormatter formatter : List.of(ISO, PLAIN_DATE_TIME)) {
                try {
                    return LocalDateTime.parse(text, formatter);
                } catch (DateTimeParseException ignored) {
                    // 尝试下一种格式
                }
            }
            try {
                return LocalDate.parse(text).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 落入 warning
            }
        }
        warnings.add("front matter 的 date 字段格式不支持，已忽略");
        return null;
    }

    /* ================================ 共用 ================================ */

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "导入文件读取失败");
        }
    }

    /**
     * 文件名小写扩展名（不含点；无扩展名返回空串）
     */
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 文件名去扩展名（取最后一个点之前；含路径分隔符时先取末段）
     */
    private static String baseNameOf(String filename) {
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * 仅作者本人或 ADMIN 可见（语义同文章详情）
     */
    private boolean isAuthorOrAdmin(Article article, Jwt jwt) {
        if (Objects.isNull(jwt)) {
            return false;
        }
        Long userId = JwtSubjects.userIdOf(jwt);
        return userId.equals(article.getAuthorId()) || isAdmin();
    }

    /**
     * 判断当前请求主体是否为 ADMIN：以安全上下文中的权限为准（由
     * DatabaseRoleJwtAuthenticationConverter 按 sub 实时读库装配），不解析 JWT roles 声明
     */
    private boolean isAdmin() {
        String adminAuthority = "ROLE_" + UserRole.ADMIN.name();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return Objects.nonNull(authentication) && authentication.getAuthorities().stream()
                .anyMatch(authority -> adminAuthority.equals(authority.getAuthority()));
    }
}

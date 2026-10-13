package com.zer0drv.blog.article.controller;

import com.zer0drv.blog.article.dto.ArticlePasswordDTO;
import com.zer0drv.blog.article.dto.ArticleSaveDTO;
import com.zer0drv.blog.article.dto.ArticleStatusDTO;
import com.zer0drv.blog.article.dto.AutosaveDTO;
import com.zer0drv.blog.article.service.ArticleService;
import com.zer0drv.blog.article.transfer.ArticleTransferService;
import com.zer0drv.blog.article.vo.ArchiveMonthVO;
import com.zer0drv.blog.article.vo.ArticleAccessVO;
import com.zer0drv.blog.article.vo.ArticleDetailVO;
import com.zer0drv.blog.article.vo.ArticleExportVO;
import com.zer0drv.blog.article.vo.ArticleImportResultVO;
import com.zer0drv.blog.article.vo.ArticleListVO;
import com.zer0drv.blog.article.vo.ArticleVersionDetailVO;
import com.zer0drv.blog.article.vo.ArticleVersionVO;
import com.zer0drv.blog.common.captcha.CaptchaGuard;
import com.zer0drv.blog.common.captcha.CaptchaService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.Result;
import com.zer0drv.blog.common.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * @author Yoruhaki
 */
@RestController
@RequestMapping("/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    private final ArticleTransferService articleTransferService;

    private final CaptchaGuard captchaGuard;

    /**
     * 口令访问令牌请求头（issue #15）：详情接口对 PASSWORD 文章凭它放行
     */
    private static final String ACCESS_TOKEN_HEADER = "X-Article-Access-Token";

    /**
     * 口令校验限流计数的文章维度键前缀（另一维度为客户端 IP，登录同款双维度编排）
     */
    private static final String PASSWORD_ARTICLE_KEY_PREFIX = "a:";

    /**
     * 已发布文章分页列表（公开）
     */
    @GetMapping
    public Result<PageResult<ArticleListVO>> pagePublished(@RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "10") long size,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Long tagId,
                                                           @RequestParam(required = false) Long categoryId) {
        return Result.ok(articleService.pagePublished(page, size, keyword, tagId, categoryId));
    }

    /**
     * 本人文章分页（含草稿 / 下架）
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<PageResult<ArticleListVO>> pageMine(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size,
                                                      @RequestParam(required = false) String status,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(articleService.pageMine(page, size, status, jwt));
    }

    /**
     * 全文搜索（公开；P0）。字面量路径优先于 /{id}，无需改 SecurityConfig
     */
    @GetMapping("/search")
    public Result<PageResult<ArticleListVO>> search(@RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size) {
        return Result.ok(articleService.search(keyword, page, size));
    }

    /**
     * 归档（公开；P0）：可见文章按月分组
     */
    @GetMapping("/archives")
    public Result<List<ArchiveMonthVO>> archives() {
        return Result.ok(articleService.archives());
    }

    /**
     * 文章详情（公开；非发布状态仅作者本人 / ADMIN 可见）。
     * 已到点的 PASSWORD 文章（issue #15）：无有效访问令牌时返回「需口令」极简视图
     * （passwordRequired=true，不下发标题 / 正文）；令牌经 X-Article-Access-Token 头携带，
     * 由口令校验端点签发。PRIVATE 对公众与「不存在」同语义。
     */
    @GetMapping("/{id}")
    public Result<ArticleDetailVO> getDetail(@PathVariable Long id,
                                             @RequestHeader(name = ACCESS_TOKEN_HEADER, required = false)
                                             String accessToken,
                                             @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(articleService.getDetail(id, jwt, accessToken));
    }

    /**
     * 按 slug 取文章详情（公开；issue #14 固定链接）。与 /{id} 并存——旧 id 链接继续可用，
     * slug 为新增能力。字面量前缀 slug 两段式路径不与 /{id}（Long）产生路由歧义，
     * SecurityConfig 的 /articles/** permitAll 已覆盖，无需改安全链。
     * 口令门禁与令牌头语义同 {@link #getDetail}
     */
    @GetMapping("/slug/{slug}")
    public Result<ArticleDetailVO> getDetailBySlug(@PathVariable String slug,
                                                   @RequestHeader(name = ACCESS_TOKEN_HEADER, required = false)
                                                   String accessToken,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return Result.ok(articleService.getDetailBySlug(slug, jwt, accessToken));
    }

    /**
     * 校验口令保护文章的访问口令（issue #15，公开）：通过则签发短期访问令牌
     * （Redis，TTL 2h），前端持令牌经 X-Article-Access-Token 头访问详情。
     * 限流防爆破：CaptchaGuard 登录同款双维度编排（IP + 文章 id），口令错误两维各 +1、
     * 成功清零，任一维度达阈值强制图形验证码。非「已到点的 PASSWORD 文章」与不存在同语义。
     */
    @PostMapping("/{id}/password")
    public Result<ArticleAccessVO> verifyAccessPassword(@PathVariable Long id,
                                                        @RequestBody @Valid ArticlePasswordDTO dto,
                                                        HttpServletRequest request) {
        String ip = IpUtils.clientIp(request);
        ArticleAccessVO access = captchaGuard.guarded(CaptchaService.SCENE_ARTICLE_PASSWORD, ip,
                PASSWORD_ARTICLE_KEY_PREFIX + id, dto.getCaptchaId(), dto.getCaptchaCode(),
                () -> articleService.verifyAccessPassword(id, dto.getPassword()));
        return Result.ok(access);
    }

    /**
     * 新建文章（允许直接发布）
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Long> create(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ArticleSaveDTO dto) {
        return Result.ok(articleService.create(dto, jwt));
    }

    /**
     * 导出文章为文件（md / html）。可见性与详情完全一致（已发布=公开；
     * 草稿/下架=本人或 ADMIN，其余报文章不存在）。format 省略时按 editorType 默认；
     * 内容为 YAML front matter（标题/发布时间/标签/分类等元数据）+ 正文原文，
     * 不做 Markdown 渲染转换。
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(required = false) String format,
                                         @AuthenticationPrincipal Jwt jwt) {
        return downloadResponse(articleTransferService.exportArticle(id, format, jwt));
    }

    /**
     * 批量导出打包 zip（issue #16，ADMIN / AUTHOR）：ids 逗号分隔或重复传参；
     * AUTHOR 只能导自己的（他人文章与不存在同语义），ADMIN 全部。
     * 字面量路径优先于 /{id}，无需改 SecurityConfig 的公开匹配（该路径已单独要求登录）。
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public ResponseEntity<byte[]> exportBatch(@RequestParam List<Long> ids,
                                              @AuthenticationPrincipal Jwt jwt) {
        return downloadResponse(articleTransferService.exportArticlesZip(ids, jwt));
    }

    /**
     * 导入文章文件（issue #16，ADMIN / AUTHOR）：.md/.markdown/.txt → MARKDOWN 草稿，
     * .html/.htm → RICHTEXT 草稿（入库前 jsoup 白名单清洗），单文件 ≤2MB；
     * 或 .zip 批量导入（≤5MB，≤50 个文件，防 Zip Slip 与解压炸弹）。
     * YAML front matter 白名单字段（title/date/tags/category/summary/cover）自动填充，
     * 一律创建 DRAFT 草稿；响应为成功草稿清单（含警告与外链图片待转存清单）+ 失败明细。
     * 属文章操作，不计入附件上传日配额。
     */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<ArticleImportResultVO> importArticle(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestParam("file") MultipartFile file) {
        return Result.ok(articleTransferService.importArticles(file, jwt));
    }

    /**
     * 组装文件下载响应：按扩展名映射 Content-Type（zip / html / markdown），
     * RFC 5987 编码文件名（filename*=UTF-8''<URL编码名>.<ext>）
     */
    private ResponseEntity<byte[]> downloadResponse(ArticleExportVO export) {
        MediaType mediaType = switch (export.extension()) {
            case "zip" -> MediaType.parseMediaType("application/zip");
            case "html" -> MediaType.parseMediaType("text/html;charset=UTF-8");
            default -> MediaType.parseMediaType("text/markdown;charset=UTF-8");
        };
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(export.fileName() + "." + export.extension(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(export.body());
    }

    /**
     * 编辑文章（仅本人或 ADMIN）
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @RequestBody @Valid ArticleSaveDTO dto) {
        articleService.update(id, dto, jwt);
        return Result.ok();
    }

    /**
     * 删除文章（仅本人或 ADMIN）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        articleService.delete(id, jwt);
        return Result.ok();
    }

    /**
     * 上架 / 下架 / 回草稿（仅本人或 ADMIN）
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> updateStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @RequestBody @Valid ArticleStatusDTO dto) {
        articleService.updateStatus(id, dto, jwt);
        return Result.ok();
    }

    /**
     * 版本列表（P0，仅本人或 ADMIN；version 倒序，不含正文）
     */
    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<List<ArticleVersionVO>> listVersions(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return Result.ok(articleService.listVersions(id, jwt));
    }

    /**
     * 版本详情（P0，仅本人或 ADMIN）
     */
    @GetMapping("/{id}/versions/{version}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<ArticleVersionDetailVO> getVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                     @PathVariable Integer version) {
        return Result.ok(articleService.getVersion(id, version, jwt));
    }

    /**
     * 恢复到指定版本（P0，仅本人或 ADMIN；恢复前对当前行留快照）
     */
    @PostMapping("/{id}/restore/{version}")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> restoreVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                       @PathVariable Integer version) {
        articleService.restoreVersion(id, version, jwt);
        return Result.ok();
    }

    /**
     * 自动保存草稿（P0，仅本人或 ADMIN，仅编辑已有文章）
     */
    @PutMapping("/{id}/autosave")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Map<String, Object>> saveAutosave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                    @RequestBody @Valid AutosaveDTO dto) {
        return Result.ok(articleService.saveAutosave(id, dto, jwt));
    }

    /**
     * 读取自动保存草稿（P0，仅本人或 ADMIN）
     */
    @GetMapping("/{id}/autosave")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Map<String, Object>> getAutosave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return Result.ok(articleService.getAutosave(id, jwt));
    }

    /**
     * 从回收站恢复（P0，仅本人或 ADMIN；回到草稿态）
     */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        articleService.restore(id, jwt);
        return Result.ok();
    }

    /**
     * 彻底删除（P0，仅本人或 ADMIN；物理删除并级联清理，不可恢复）
     */
    @DeleteMapping("/{id}/force")
    @PreAuthorize("hasAnyRole('ADMIN','AUTHOR')")
    public Result<Void> forceDelete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        articleService.forceDelete(id, jwt);
        return Result.ok();
    }
}

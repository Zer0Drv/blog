package com.zer0drv.blog.seo.service;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.article.api.ArticleFeedEntry;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.site.service.SiteConfigService;
import com.zer0drv.blog.user.domain.User;
import com.zer0drv.blog.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SEO 三件套：RSS/Atom 订阅源（Rome 生成）+ sitemap.xml（手写 urlset）+ robots.txt。
 * 仅收录对外可见文章（经 article.api.ArticleCatalog，谓词出处 ArticleVisibility），
 * 定时中与草稿/下架文章不出现。
 *
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class SeoService {

    /**
     * 订阅源条目数上限
     */
    private static final int FEED_LIMIT = 20;

    /**
     * sitemap 文章 URL 上限
     */
    private static final int SITEMAP_LIMIT = 1000;

    private static final DateTimeFormatter SITEMAP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ArticleCatalog articleCatalog;
    private final UserService userService;
    private final SiteConfigService siteConfigService;

    /**
     * RSS 2.0 订阅源
     */
    public String buildRss() {
        return outputFeed("rss_2.0");
    }

    /**
     * Atom 1.0 订阅源
     */
    public String buildAtom() {
        return outputFeed("atom_1.0");
    }

    /**
     * sitemap.xml：首页 + 可见文章（lastmod=文章 updateTime），上限 1000 条
     */
    public String buildSitemap() {
        String baseUrl = baseUrl();
        List<ArticleFeedEntry> articles = articleCatalog.listVisibleLatest(SITEMAP_LIMIT);
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        xml.append("  <url><loc>").append(escapeXml(baseUrl + "/")).append("</loc></url>\n");
        for (ArticleFeedEntry article : articles) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(articleLink(baseUrl, article.id()))).append("</loc>\n");
            if (Objects.nonNull(article.updateTime())) {
                xml.append("    <lastmod>")
                        .append(article.updateTime().format(SITEMAP_DATE_FORMATTER))
                        .append("</lastmod>\n");
            }
            xml.append("  </url>\n");
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    /**
     * robots.txt：放行全站并声明 sitemap 地址
     */
    public String buildRobots() {
        return "User-agent: *\nAllow: /\nSitemap: " + baseUrl() + "/sitemap.xml\n";
    }

    /**
     * 用 Rome 生成指定类型的订阅源（channel 信息取站点配置）
     */
    private String outputFeed(String feedType) {
        String baseUrl = baseUrl();
        SyndFeed feed = new SyndFeedImpl();
        feed.setFeedType(feedType);
        // 显式 UTF-8：Rome 的 XmlWriter 默认按 us-ascii 转义，非 ASCII（中文标题/昵称）会被写成 '?'
        feed.setEncoding("UTF-8");
        feed.setTitle(siteConfigService.getValue("site.name", "Blog"));
        feed.setLink(baseUrl);
        feed.setDescription(siteConfigService.getValue("site.description", ""));

        List<ArticleFeedEntry> articles = articleCatalog.listVisibleLatest(FEED_LIMIT);
        Map<Long, User> authors = authorsOf(articles);
        List<SyndEntry> entries = new ArrayList<>(articles.size());
        for (ArticleFeedEntry article : articles) {
            SyndEntry entry = new SyndEntryImpl();
            entry.setTitle(article.title());
            String link = articleLink(baseUrl, article.id());
            entry.setLink(link);
            entry.setUri(link);
            User author = authors.get(article.authorId());
            if (Objects.nonNull(author)) {
                entry.setAuthor(author.getNickname());
            }
            if (Objects.nonNull(article.publishTime())) {
                entry.setPublishedDate(toDate(article.publishTime()));
            }
            SyndContent description = new SyndContentImpl();
            description.setType("text");
            description.setValue(article.summary());
            entry.setDescription(description);
            entries.add(entry);
        }
        feed.setEntries(entries);
        try {
            return new SyndFeedOutput().outputString(feed);
        } catch (FeedException e) {
            throw new BusinessException(StatusCode.INTERNAL_SERVER_ERROR.getCode(), "订阅源生成失败");
        }
    }

    /**
     * 批量取作者（id -> User），用于 entry author 昵称
     */
    private Map<Long, User> authorsOf(List<ArticleFeedEntry> articles) {
        List<Long> authorIds = articles.stream().map(ArticleFeedEntry::authorId).distinct().toList();
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private String baseUrl() {
        String baseUrl = siteConfigService.getValue("site.base_url", "http://localhost:5173");
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String articleLink(String baseUrl, Long articleId) {
        return baseUrl + "/article/" + articleId;
    }

    private static Date toDate(LocalDateTime time) {
        return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * XML 特殊字符转义（sitemap 手写部分用；Rome 输出由框架自处理）
     */
    static String escapeXml(String text) {
        if (Objects.isNull(text)) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

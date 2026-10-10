package com.zer0drv.blog.seo.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.domain.ArticleVisibility;
import com.zer0drv.blog.article.mapper.ArticleMapper;
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
 * 仅收录对外可见文章（谓词见 {@link ArticleVisibility}），定时中与草稿/下架文章不出现。
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

    private final ArticleMapper articleMapper;
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
        List<Article> articles = pageVisibleArticles(SITEMAP_LIMIT);
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        xml.append("  <url><loc>").append(escapeXml(baseUrl + "/")).append("</loc></url>\n");
        for (Article article : articles) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(articleLink(baseUrl, article.getId()))).append("</loc>\n");
            if (Objects.nonNull(article.getUpdateTime())) {
                xml.append("    <lastmod>")
                        .append(article.getUpdateTime().format(SITEMAP_DATE_FORMATTER))
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

        List<Article> articles = pageVisibleArticles(FEED_LIMIT);
        Map<Long, User> authors = authorsOf(articles);
        List<SyndEntry> entries = new ArrayList<>(articles.size());
        for (Article article : articles) {
            SyndEntry entry = new SyndEntryImpl();
            entry.setTitle(article.getTitle());
            String link = articleLink(baseUrl, article.getId());
            entry.setLink(link);
            entry.setUri(link);
            User author = authors.get(article.getAuthorId());
            if (Objects.nonNull(author)) {
                entry.setAuthor(author.getNickname());
            }
            if (Objects.nonNull(article.getPublishTime())) {
                entry.setPublishedDate(toDate(article.getPublishTime()));
            }
            SyndContent description = new SyndContentImpl();
            description.setType("text");
            description.setValue(article.getSummary());
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
     * 可见文章分页查询（publish_time 倒序；MP 逻辑删除自动拼 deleted=0）
     */
    private List<Article> pageVisibleArticles(int limit) {
        Page<Article> page = articleMapper.selectPage(new Page<>(1, limit),
                ArticleVisibility.apply(Wrappers.lambdaQuery(Article.class))
                        .orderByDesc(Article::getPublishTime));
        return page.getRecords();
    }

    /**
     * 批量取作者（id -> User），用于 entry author 昵称
     */
    private Map<Long, User> authorsOf(List<Article> articles) {
        List<Long> authorIds = articles.stream().map(Article::getAuthorId).distinct().toList();
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

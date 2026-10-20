package com.zer0drv.blog.seo.service;

import com.zer0drv.blog.article.api.ArticleCatalog;
import com.zer0drv.blog.site.service.SiteConfigService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;

/** 在当前 SPA 入口中插入公共 metadata；不渲染正文，不使用登录身份绕过可见性。 */
@Service
@RequiredArgsConstructor
public class ArticleHtmlService {
    private final ArticleCatalog articles;
    private final SiteConfigService siteConfig;
    private final FrontendShell frontend;

    public String render(String key) throws IOException {
        Document document = Jsoup.parse(frontend.load());
        document.select("title, meta[name=description], meta[name=robots], meta[property^=og:], "
                + "meta[name^=twitter:], link[rel=canonical]").remove();
        String site = text(siteConfig.getValue("site.name", "Blog"), 200);
        String description = text(siteConfig.getValue("site.description", ""), 320);
        document.title(site);
        meta(document, "property", "og:site_name", site);
        var article = articles.findVisibleFeedEntryByKey(key);
        String base = baseUrl(siteConfig.getValue("site.base_url", ""));
        if (article.isPresent() && !base.isEmpty()) {
            var entry = article.get();
            String title = text(entry.title(), 200) + " - " + site;
            description = text(entry.summary(), 320);
            String slug = entry.slug();
            String canonical = base + "/article/"
                    + (slug != null && slug.matches("[a-z0-9-]+") ? slug : entry.id());
            document.title(title);
            meta(document, "property", "og:title", title);
            meta(document, "property", "og:type", "article");
            meta(document, "property", "og:url", canonical);
            document.head().appendElement("link").attr("rel", "canonical").attr("href", canonical);
        } else {
            meta(document, "property", "og:title", site);
            meta(document, "property", "og:type", "website");
            meta(document, "name", "robots", "noindex, nofollow");
        }
        meta(document, "name", "description", description);
        meta(document, "property", "og:description", description);
        return document.outerHtml();
    }

    private static void meta(Document document, String attribute, String name, String content) {
        document.head().appendElement("meta").attr(attribute, name).attr("content", content);
    }

    private static String text(String value, int limit) {
        String plain = Jsoup.parse(value == null ? "" : value).text();
        return plain.substring(0, plain.offsetByCodePoints(0, Math.min(plain.codePointCount(0, plain.length()), limit)));
    }

    static String baseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) return "";
            return value.replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }
}

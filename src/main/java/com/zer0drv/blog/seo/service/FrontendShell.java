package com.zer0drv.blog.seo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 只读取运维配置的当前前端入口；不接受请求 URL，不跟随重定向，不缓存文章信息。 */
@Component
public class FrontendShell {
    private static final int MAX_BYTES = 1024 * 1024;
    private final String indexUrl;

    public FrontendShell(@Value("${blog.seo.frontend-index-url:}") String indexUrl) {
        this.indexUrl = indexUrl;
    }

    public String load() throws IOException {
        URI uri;
        try {
            uri = URI.create(indexUrl);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid frontend index configuration", exception);
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("Frontend index URL must be configured");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(3000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Frontend index unavailable");
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid frontend index size");
                String html = new String(bytes, StandardCharsets.UTF_8);
                if (!org.jsoup.Jsoup.parse(html).select("#app").isEmpty()) return html;
                throw new IOException("Frontend index has no application root");
            }
        } finally {
            connection.disconnect();
        }
    }
}

package com.zer0drv.blog.seo.controller;

import com.zer0drv.blog.seo.service.SeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SEO 三件套（匿名可访问，SecurityConfig 已 permitAll）：RSS/Atom 订阅源、sitemap、robots。
 *
 * @author Yoruhaki
 */
@RestController
@RequiredArgsConstructor
public class SeoController {

    private final SeoService seoService;

    /**
     * RSS 2.0 订阅源（最近 20 篇可见文章）
     */
    @GetMapping(value = "/rss.xml", produces = "application/rss+xml;charset=UTF-8")
    public String rss() {
        return seoService.buildRss();
    }

    /**
     * Atom 1.0 订阅源（最近 20 篇可见文章）
     */
    @GetMapping(value = "/atom.xml", produces = "application/atom+xml;charset=UTF-8")
    public String atom() {
        return seoService.buildAtom();
    }

    /**
     * sitemap.xml（首页 + ≤1000 篇可见文章）
     */
    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    public String sitemap() {
        return seoService.buildSitemap();
    }

    /**
     * robots.txt
     */
    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    public String robots() {
        return seoService.buildRobots();
    }
}

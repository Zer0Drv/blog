package com.zer0drv.blog.article.api;

import java.util.List;

/** Public series links for sitemap consumers; article persistence stays inside its domain. */
public interface SeriesCatalog {
    List<SeriesLink> publishedLinks();
}

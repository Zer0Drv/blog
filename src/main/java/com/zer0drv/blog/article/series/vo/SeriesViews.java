package com.zer0drv.blog.article.series.vo;

import java.time.LocalDateTime;
import java.util.List;

public final class SeriesViews {
    private SeriesViews() { }

    public record Chapter(long id, String slug, String title, LocalDateTime publishTime) { }

    public record ManagedChapter(long id, String slug, String title, LocalDateTime publishTime,
                                 String status, String visibility, boolean visible) { }

    public record Summary(long id, String slug, String title, String description, String cover,
                          long ownerId, String ownerName, long articleCount, LocalDateTime updateTime) { }

    public record Detail(long id, String slug, String title, String description, String cover,
                         long ownerId, String ownerName, long articleCount, LocalDateTime updateTime,
                         List<Chapter> articles) { }

    public record ManagedSummary(long id, String slug, String title, String description, String cover,
                                 long ownerId, String ownerName, long articleCount, LocalDateTime updateTime,
                                 String status, long revision) { }

    public record ManagedDetail(long id, String slug, String title, String description, String cover,
                                long ownerId, String ownerName, long articleCount, LocalDateTime updateTime,
                                String status, long revision, List<ManagedChapter> articles) { }

    public record Membership(long id, String slug, String title, long articleCount,
                             Chapter previous, Chapter next) { }
}

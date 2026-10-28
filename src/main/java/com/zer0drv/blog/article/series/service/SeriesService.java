package com.zer0drv.blog.article.series.service;

import com.zer0drv.blog.article.api.ArticleCascade;
import com.zer0drv.blog.article.api.SeriesCatalog;
import com.zer0drv.blog.article.api.SeriesLink;
import com.zer0drv.blog.article.domain.ArticleSlugger;
import com.zer0drv.blog.article.series.dto.SeriesSaveDTO;
import com.zer0drv.blog.article.series.mapper.SeriesRepository;
import com.zer0drv.blog.article.series.mapper.SeriesRepository.SeriesRow;
import com.zer0drv.blog.article.series.vo.SeriesViews.*;
import com.zer0drv.blog.attachment.service.AttachmentReferenceGuard;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.JwtSubjects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SeriesService implements SeriesCatalog, ArticleCascade {
    private final SeriesRepository repository;
    private final AttachmentReferenceGuard attachments;

    @Transactional(readOnly = true)
    public PageResult<Summary> publicList(long page, long size) {
        long offset = offset(page, size);
        return PageResult.of(repository.list(true, null, offset, size).stream().map(SeriesService::summary).toList(),
                repository.count(true, null), page, size);
    }

    @Transactional(readOnly = true)
    public Detail publicDetail(String slug) {
        var row = require(repository.findPublished(slug));
        var chapters = repository.publicChapters(row.id());
        return new Detail(row.id(), row.slug(), row.title(), row.description(), row.cover(), row.ownerId(),
                row.ownerName(), chapters.size(), row.updateTime(), chapters);
    }

    @Transactional(readOnly = true)
    public List<Membership> byArticle(long articleId) {
        if (!repository.sourceVisible(articleId)) return List.of();
        List<Membership> result = new ArrayList<>();
        for (var row : repository.memberships(articleId)) {
            var chapters = repository.publicChapters(row.id());
            for (int index = 0; index < chapters.size(); index++) {
                if (chapters.get(index).id() == articleId) {
                    result.add(new Membership(row.id(), row.slug(), row.title(), chapters.size(),
                            index == 0 ? null : chapters.get(index - 1),
                            index + 1 == chapters.size() ? null : chapters.get(index + 1)));
                    break;
                }
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PageResult<ManagedSummary> manageList(long page, long size, boolean all, Jwt jwt) {
        long offset = offset(page, size);
        if (all && !isAdmin()) throw new BusinessException(StatusCode.NOT_AUTHOR);
        Long ownerId = all ? null : JwtSubjects.userIdOf(jwt);
        return PageResult.of(repository.list(false, ownerId, offset, size).stream().map(row -> new ManagedSummary(
                row.id(), row.slug(), row.title(), row.description(), row.cover(), row.ownerId(), row.ownerName(),
                row.articleCount(), row.updateTime(), row.status(), row.revision())).toList(),
                repository.count(false, ownerId), page, size);
    }

    @Transactional(readOnly = true)
    public ManagedDetail manageDetail(long id, Jwt jwt) {
        var row = require(repository.find(id));
        assertOwner(row, jwt);
        return detail(row);
    }

    @Transactional(readOnly = true)
    public PageResult<ManagedChapter> candidates(Long seriesId, String keyword, long page, long size, Jwt jwt) {
        long offset = offset(page, size);
        if (keyword.length() > 200) throw invalid("搜索词不能超过 200 个字符");
        long ownerId = JwtSubjects.userIdOf(jwt);
        if (seriesId != null) {
            var row = require(repository.find(seriesId));
            assertOwner(row, jwt);
            ownerId = row.ownerId();
        }
        return PageResult.of(repository.candidates(ownerId, keyword.trim(), offset, size),
                repository.candidateCount(ownerId, keyword.trim()), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public ManagedDetail save(Long id, SeriesSaveDTO dto, Jwt jwt) {
        validate(dto, id != null);
        // Same lock order as article/page saves and the attachment cleaner.
        attachments.protect(dto.cover());
        long ownerId = JwtSubjects.userIdOf(jwt);
        if (id != null) {
            var row = require(repository.lock(id));
            assertOwner(row, jwt);
            assertRevision(row, dto.revision());
            ownerId = row.ownerId();
        }
        if (!repository.lockOwnedArticles(ownerId, dto.articleIds())) {
            throw invalid("系列只能收录所属作者未删除的文章，请刷新后重新选择");
        }
        try {
            if (id == null) id = repository.insert(ownerId, dto);
            else if (repository.update(id, dto) != 1) throw new BusinessException(StatusCode.SERIES_CONFLICT);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(StatusCode.SERIES_SLUG_EXISTS);
        }
        repository.replaceChapters(id, dto.articleIds());
        return detail(require(repository.find(id)));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long id, long revision, Jwt jwt) {
        if (revision < 0) throw invalid("revision 不能为负数");
        var row = require(repository.lock(id));
        assertOwner(row, jwt);
        assertRevision(row, revision);
        if (repository.delete(id, revision) != 1) throw new BusinessException(StatusCode.SERIES_CONFLICT);
    }

    @Override
    public List<SeriesLink> publishedLinks() { return repository.publishedLinks(); }

    /** Called synchronously inside the article's physical deletion transaction. */
    @Override
    public void purgeByArticleId(long articleId) { repository.purgeArticle(articleId); }

    private ManagedDetail detail(SeriesRow row) {
        var chapters = repository.managedChapters(row.id());
        return new ManagedDetail(row.id(), row.slug(), row.title(), row.description(), row.cover(), row.ownerId(),
                row.ownerName(), chapters.stream().filter(ManagedChapter::visible).count(), row.updateTime(),
                row.status(), row.revision(), chapters);
    }

    private static Summary summary(SeriesRow row) {
        return new Summary(row.id(), row.slug(), row.title(), row.description(), row.cover(), row.ownerId(),
                row.ownerName(), row.articleCount(), row.updateTime());
    }

    private static void validate(SeriesSaveDTO dto, boolean update) {
        if (!ArticleSlugger.isValid(dto.slug()) || Set.of("manage", "by-article").contains(dto.slug()))
            throw invalid("slug 仅允许小写字母、数字与单连字符，且不能使用 manage 或 by-article");
        if (dto.articleIds().size() != new HashSet<>(dto.articleIds()).size()) throw invalid("系列不能重复收录同一篇文章");
        if (update && dto.revision() == null) throw invalid("更新系列必须携带 revision");
        if (!update && dto.revision() != null && dto.revision() != 0) throw invalid("新系列的 revision 必须为 0");
    }

    private static SeriesRow require(SeriesRow row) {
        if (row == null) throw new BusinessException(StatusCode.SERIES_NOT_EXIST);
        return row;
    }

    private static void assertOwner(SeriesRow row, Jwt jwt) {
        if (row.ownerId() != JwtSubjects.userIdOf(jwt) && !isAdmin()) throw new BusinessException(StatusCode.NOT_AUTHOR);
    }

    private static void assertRevision(SeriesRow row, long revision) {
        if (row.revision() != revision) throw new BusinessException(StatusCode.SERIES_CONFLICT);
    }

    private static boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static long offset(long page, long size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100) throw invalid("page 需为 1–1000000，size 需为 1–100");
        return (page - 1) * size;
    }

    private static BusinessException invalid(String message) { return new BusinessException(StatusCode.PARAM_INVALID.getCode(), message); }
}

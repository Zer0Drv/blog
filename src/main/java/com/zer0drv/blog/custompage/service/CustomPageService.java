package com.zer0drv.blog.custompage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.common.util.PageNavigationSlugs;
import com.zer0drv.blog.custompage.api.PageCatalog;
import com.zer0drv.blog.custompage.api.PageLink;
import com.zer0drv.blog.custompage.domain.CustomPage;
import com.zer0drv.blog.custompage.dto.PageSaveDTO;
import com.zer0drv.blog.custompage.mapper.CustomPageMapper;
import com.zer0drv.blog.custompage.vo.PageVO;
import com.zer0drv.blog.site.service.SiteConfigService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomPageService extends ServiceImpl<CustomPageMapper, CustomPage> implements PageCatalog {
    private final SiteConfigService siteConfigService;

    public PageResult<PageVO> adminList(long page, long size) {
        if (page < 1 || size < 1 || size > 500) throw new BusinessException(StatusCode.PARAM_INVALID);
        Page<CustomPage> result = page(new Page<>(page, size), Wrappers.lambdaQuery(CustomPage.class)
                .select(CustomPage::getId, CustomPage::getSlug, CustomPage::getTitle, CustomPage::getEditorType,
                        CustomPage::getStatus, CustomPage::getCreateTime, CustomPage::getUpdateTime)
                .orderByDesc(CustomPage::getUpdateTime).orderByDesc(CustomPage::getId));
        return PageResult.of(result.getRecords().stream().map(this::toVO).toList(), result.getTotal(), page, size);
    }

    public PageVO adminDetail(Long id) { return toVO(requirePage(id)); }

    public PageVO publicDetail(String slug) {
        CustomPage page = getOne(publishedQuery().eq(CustomPage::getSlug, slug));
        if (page == null) throw new BusinessException(StatusCode.PAGE_NOT_EXIST);
        return toVO(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long savePage(Long id, PageSaveDTO dto) {
        CustomPage page = id == null ? new CustomPage() : requirePage(id);
        Long owner = baseMapper.findSlugOwner(dto.getSlug());
        if (owner != null && !owner.equals(id)) throw new BusinessException(StatusCode.PAGE_SLUG_EXISTS);
        page.setSlug(dto.getSlug());
        page.setTitle(dto.getTitle().trim());
        page.setContent("RICHTEXT".equals(dto.getEditorType())
                ? Jsoup.clean(dto.getContent(), Safelist.relaxed()) : dto.getContent());
        page.setEditorType(dto.getEditorType());
        page.setStatus(dto.getStatus());
        try {
            if (id == null) save(page); else updateById(page);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(StatusCode.PAGE_SLUG_EXISTS);
        }
        return page.getId();
    }

    public void deletePage(Long id) { requirePage(id); removeById(id); }

    @Override
    public List<PageLink> publishedLinks() {
        return list(publishedQuery()
                .select(CustomPage::getId, CustomPage::getSlug, CustomPage::getTitle, CustomPage::getUpdateTime)
                .orderByAsc(CustomPage::getId).last("LIMIT 1000"))
                .stream().map(p -> new PageLink(p.getId(), p.getSlug(), p.getTitle(), p.getUpdateTime())).toList();
    }

    public List<PageLink> navigation() {
        List<String> slugs;
        try { slugs = PageNavigationSlugs.parse(siteConfigService.getValue(PageNavigationSlugs.CONFIG_KEY, "[]")); }
        catch (BusinessException e) { return List.of(); }
        if (slugs.isEmpty()) return List.of();
        Map<String, PageLink> published = list(publishedQuery()
                .select(CustomPage::getId, CustomPage::getSlug, CustomPage::getTitle, CustomPage::getUpdateTime)
                .in(CustomPage::getSlug, slugs)).stream()
                .map(p -> new PageLink(p.getId(), p.getSlug(), p.getTitle(), p.getUpdateTime()))
                .collect(Collectors.toMap(PageLink::slug, Function.identity()));
        return slugs.stream().map(published::get).filter(Objects::nonNull).toList();
    }

    /** 三个公开出口共用发布判定；逻辑删除由 MP 统一追加。 */
    private LambdaQueryWrapper<CustomPage> publishedQuery() {
        return Wrappers.lambdaQuery(CustomPage.class).eq(CustomPage::getStatus, "PUBLISHED");
    }

    private CustomPage requirePage(Long id) {
        CustomPage page = getById(id);
        if (page == null) throw new BusinessException(StatusCode.PAGE_NOT_EXIST);
        return page;
    }

    private PageVO toVO(CustomPage p) {
        return new PageVO(p.getId(), p.getSlug(), p.getTitle(), p.getContent(), p.getEditorType(),
                p.getStatus(), p.getCreateTime(), p.getUpdateTime());
    }
}

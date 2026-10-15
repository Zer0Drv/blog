package com.zer0drv.blog.friendlink.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.response.StatusCode;
import com.zer0drv.blog.friendlink.domain.FriendLink;
import com.zer0drv.blog.friendlink.dto.FriendLinkSaveDTO;
import com.zer0drv.blog.friendlink.mapper.FriendLinkMapper;
import com.zer0drv.blog.friendlink.service.FriendLinkService;
import com.zer0drv.blog.friendlink.vo.FriendLinkVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 友情链接实现：DB 持久化 + 启用列表内存缓存（参照 SensitiveWordServiceImpl 的
 * AtomicReference + TTL 兜底模式，写操作即时失效）。
 * 友链量小且读多写少，不依赖 Redis。
 *
 * @author Yoruhaki
 */
@Service
public class FriendLinkServiceImpl extends ServiceImpl<FriendLinkMapper, FriendLink>
        implements FriendLinkService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.zer0drv.blog.attachment.service.AttachmentReferenceGuard attachmentReferences;

    /**
     * 启用列表缓存 TTL 兜底：即使存在绕过本服务的改库路径，也最多滞后 5 分钟生效
     */
    private static final long PUBLIC_CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    /**
     * 启用列表缓存（null = 未加载/已失效）
     */
    private final AtomicReference<List<FriendLinkVO>> publicCache = new AtomicReference<>();

    /**
     * 缓存过期时间点（epoch millis）；与 publicCache 配对使用，失效时一并归零
     */
    private volatile long publicCacheExpiresAt = 0L;

    @Override
    public List<FriendLinkVO> listPublic() {
        List<FriendLinkVO> cached = publicCache.get();
        if (Objects.nonNull(cached) && System.currentTimeMillis() < publicCacheExpiresAt) {
            return cached;
        }
        List<FriendLinkVO> links = list(Wrappers.lambdaQuery(FriendLink.class)
                        .eq(FriendLink::getStatus, FriendLink.STATUS_ENABLED)
                        .orderByAsc(FriendLink::getSort)
                        .orderByAsc(FriendLink::getId))
                .stream()
                .map(this::toVO)
                .toList();
        publicCache.set(links);
        publicCacheExpiresAt = System.currentTimeMillis() + PUBLIC_CACHE_TTL_MILLIS;
        return links;
    }

    @Override
    public PageResult<FriendLinkVO> page(long page, long size, String keyword, Short status) {
        boolean hasKeyword = Objects.nonNull(keyword) && !keyword.isBlank();
        Page<FriendLink> result = page(new Page<>(page, size), Wrappers.lambdaQuery(FriendLink.class)
                .and(hasKeyword, w -> w.like(FriendLink::getName, keyword)
                        .or()
                        .like(FriendLink::getUrl, keyword))
                .eq(Objects.nonNull(status), FriendLink::getStatus, status)
                .orderByAsc(FriendLink::getSort)
                .orderByDesc(FriendLink::getId));
        List<FriendLinkVO> records = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(records, result.getTotal(), page, size);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Long add(FriendLinkSaveDTO dto) {
        attachmentReferences.protect(dto.getUrl(), dto.getLogo());
        FriendLink link = new FriendLink();
        applyDto(link, dto);
        link.setStatus(FriendLink.STATUS_ENABLED);
        save(link);
        invalidatePublicCache();
        return link.getId();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void update(Long id, FriendLinkSaveDTO dto) {
        attachmentReferences.protect(dto.getUrl(), dto.getLogo());
        FriendLink link = requireExists(id);
        applyDto(link, dto);
        updateById(link);
        invalidatePublicCache();
    }

    @Override
    public void setStatus(Long id, Short status) {
        if (Objects.isNull(status)
                || (status != FriendLink.STATUS_ENABLED && status != FriendLink.STATUS_DISABLED)) {
            throw new BusinessException(StatusCode.PARAM_INVALID);
        }
        FriendLink link = requireExists(id);
        link.setStatus(status);
        updateById(link);
        invalidatePublicCache();
    }

    @Override
    public void delete(Long id) {
        removeById(id);
        invalidatePublicCache();
    }

    /**
     * 取存在性（逻辑删除后的记录视为不存在）
     */
    private FriendLink requireExists(Long id) {
        FriendLink link = getById(id);
        if (Objects.isNull(link)) {
            throw new BusinessException(StatusCode.FRIEND_LINK_NOT_EXIST);
        }
        return link;
    }

    private void applyDto(FriendLink link, FriendLinkSaveDTO dto) {
        link.setName(dto.getName().trim());
        link.setUrl(dto.getUrl().trim());
        link.setLogo(Objects.nonNull(dto.getLogo()) ? dto.getLogo().trim() : "");
        link.setDescription(Objects.nonNull(dto.getDescription()) ? dto.getDescription().trim() : "");
        link.setSort(Objects.nonNull(dto.getSort()) ? dto.getSort() : 0);
    }

    private void invalidatePublicCache() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { clearPublicCache(); }
                    });
        } else clearPublicCache();
    }

    private void clearPublicCache() {
        publicCache.set(null);
        publicCacheExpiresAt = 0L;
    }

    private FriendLinkVO toVO(FriendLink link) {
        FriendLinkVO vo = new FriendLinkVO();
        vo.setId(link.getId());
        vo.setName(link.getName());
        vo.setUrl(link.getUrl());
        vo.setLogo(link.getLogo());
        vo.setDescription(link.getDescription());
        vo.setSort(link.getSort());
        vo.setStatus(link.getStatus());
        vo.setCreateTime(link.getCreateTime());
        return vo;
    }
}

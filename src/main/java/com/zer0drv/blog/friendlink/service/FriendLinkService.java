package com.zer0drv.blog.friendlink.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.friendlink.domain.FriendLink;
import com.zer0drv.blog.friendlink.dto.FriendLinkSaveDTO;
import com.zer0drv.blog.friendlink.vo.FriendLinkVO;

import java.util.List;

/**
 * 友情链接（issue #17）。公众端只读启用项（sort 升序），管理端 CRUD + 启停。
 * 缓存参照 SensitiveWordServiceImpl：启用列表全量内存缓存 + 写操作即时失效。
 *
 * @author Yoruhaki
 */
public interface FriendLinkService extends IService<FriendLink> {

    /**
     * 公众端列表：仅启用状态，sort 升序（id 升序兜底）
     */
    List<FriendLinkVO> listPublic();

    /**
     * 管理端分页（keyword 模糊匹配 name/url；status 可选过滤；sort 升序 + id 倒序）
     */
    PageResult<FriendLinkVO> page(long page, long size, String keyword, Short status);

    /**
     * 新增友情链接（trim 后落库，默认启用）
     */
    Long add(FriendLinkSaveDTO dto);

    /**
     * 编辑友情链接（不存在抛 FRIEND_LINK_NOT_EXIST）
     */
    void update(Long id, FriendLinkSaveDTO dto);

    /**
     * 启用/停用（不存在抛 FRIEND_LINK_NOT_EXIST；非法状态值抛 PARAM_INVALID）
     */
    void setStatus(Long id, Short status);

    /**
     * 删除友情链接（逻辑删除）
     */
    void delete(Long id);
}

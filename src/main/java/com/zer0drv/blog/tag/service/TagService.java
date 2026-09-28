package com.zer0drv.blog.tag.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.tag.domain.Tag;
import com.zer0drv.blog.tag.dto.TagSaveDTO;
import com.zer0drv.blog.tag.vo.TagVO;

import java.util.List;

/**
 * @author Yoruhaki
 */
public interface TagService extends IService<Tag> {

    /**
     * 全部标签
     */
    List<TagVO> listAll();

    /**
     * 新建标签，返回标签id
     */
    Long create(TagSaveDTO dto);

    /**
     * 编辑标签
     */
    void update(Long id, TagSaveDTO dto);

    /**
     * 删除标签（同时清理文章关联）
     */
    void delete(Long id);
}
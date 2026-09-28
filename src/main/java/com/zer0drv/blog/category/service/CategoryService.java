package com.zer0drv.blog.category.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.category.domain.Category;
import com.zer0drv.blog.category.dto.CategorySaveDTO;
import com.zer0drv.blog.category.vo.CategoryVO;

import java.util.List;

/**
 * @author Yoruhaki
 */
public interface CategoryService extends IService<Category> {

    /**
     * 分类树（按 sort 升序）
     */
    List<CategoryVO> tree();

    /**
     * 新建分类，返回分类id
     */
    Long create(CategorySaveDTO dto);

    /**
     * 编辑分类
     */
    void update(Long id, CategorySaveDTO dto);

    /**
     * 删除分类（有子分类或文章引用时拒绝）
     */
    void delete(Long id);
}
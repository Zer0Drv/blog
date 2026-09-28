package com.zer0drv.blog.category.vo;

import lombok.Data;

import java.util.List;

/**
 * 分类树节点
 *
 * @author Yoruhaki
 */
@Data
public class CategoryVO {

    private Long id;

    private String name;

    private Long parentId;

    private Integer sort;

    private List<CategoryVO> children;
}
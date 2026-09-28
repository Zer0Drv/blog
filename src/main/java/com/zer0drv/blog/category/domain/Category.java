package com.zer0drv.blog.category.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zer0drv.blog.category.vo.CategoryVO;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分类表（parent_id = 0 为根分类）
 *
 * @author Yoruhaki
 */
@Data
@TableName(value = "category")
@AutoMapper(target = CategoryVO.class, reverseConvertGenerate = false)
public class Category {

    /**
     * 分类id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 分类名
     */
    @TableField(value = "name")
    private String name;

    /**
     * 父分类id：0=根分类
     */
    @TableField(value = "parent_id")
    private Long parentId;

    /**
     * 排序值，越小越靠前
     */
    @TableField(value = "sort")
    private Integer sort;

    /**
     * 创建时间
     */
    @TableField(value = "create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除；1-删除
     */
    @TableField(value = "deleted")
    private Short deleted;
}
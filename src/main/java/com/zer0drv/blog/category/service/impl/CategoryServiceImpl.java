package com.zer0drv.blog.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zer0drv.blog.article.domain.Article;
import com.zer0drv.blog.article.mapper.ArticleMapper;
import com.zer0drv.blog.category.domain.Category;
import com.zer0drv.blog.category.dto.CategorySaveDTO;
import com.zer0drv.blog.category.mapper.CategoryMapper;
import com.zer0drv.blog.category.service.CategoryService;
import com.zer0drv.blog.category.vo.CategoryVO;
import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author Yoruhaki
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    /**
     * 根分类的 parent_id
     */
    private static final long ROOT_PARENT_ID = 0L;

    private final ArticleMapper articleMapper;
    private final Converter converter;

    @Override
    public List<CategoryVO> tree() {
        List<Category> categories = list();
        Map<Long, CategoryVO> nodeMap = new LinkedHashMap<>();
        for (Category category : categories) {
            CategoryVO node = converter.convert(category, CategoryVO.class);
            node.setChildren(new ArrayList<>());
            nodeMap.put(category.getId(), node);
        }
        List<CategoryVO> roots = new ArrayList<>();
        for (CategoryVO node : nodeMap.values()) {
            CategoryVO parent = Objects.nonNull(node.getParentId()) ? nodeMap.get(node.getParentId()) : null;
            if (Objects.nonNull(parent)) {
                parent.getChildren().add(node);
            } else {
                // parent_id = 0 或父分类已删除的视为根节点，避免数据悬空后整棵子树丢失
                roots.add(node);
            }
        }
        Comparator<CategoryVO> bySort = Comparator.comparing(
                node -> Objects.nonNull(node.getSort()) ? node.getSort() : 0);
        roots.sort(bySort);
        nodeMap.values().forEach(node -> node.getChildren().sort(bySort));
        return roots;
    }

    @Override
    public Long create(CategorySaveDTO dto) {
        Category category = new Category();
        category.setName(dto.getName().trim());
        long parentId = resolveParentId(dto.getParentId(), null);
        category.setParentId(parentId);
        category.setSort(Objects.nonNull(dto.getSort()) ? dto.getSort() : 0);
        save(category);
        return category.getId();
    }

    @Override
    public void update(Long id, CategorySaveDTO dto) {
        Category category = requireCategory(id);
        category.setName(dto.getName().trim());
        category.setParentId(resolveParentId(dto.getParentId(), id));
        if (Objects.nonNull(dto.getSort())) {
            category.setSort(dto.getSort());
        }
        updateById(category);
    }

    @Override
    public void delete(Long id) {
        requireCategory(id);
        long childCount = count(Wrappers.lambdaQuery(Category.class).eq(Category::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException(StatusCode.CATEGORY_HAS_CHILDREN);
        }
        long articleCount = articleMapper.selectCount(Wrappers.lambdaQuery(Article.class)
                .eq(Article::getCategoryId, id));
        if (articleCount > 0) {
            throw new BusinessException(StatusCode.CATEGORY_HAS_ARTICLES);
        }
        removeById(id);
    }

    private Category requireCategory(Long id) {
        Category category = getById(id);
        if (Objects.isNull(category)) {
            throw new BusinessException(StatusCode.CATEGORY_NOT_EXIST);
        }
        return category;
    }

    /**
     * 解析父分类：缺省 0=根；非 0 校验存在且不能是自身
     */
    private long resolveParentId(Long parentId, Long selfId) {
        if (Objects.isNull(parentId) || parentId == ROOT_PARENT_ID) {
            return ROOT_PARENT_ID;
        }
        if (Objects.nonNull(selfId) && parentId.equals(selfId)) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "父分类不能是自身");
        }
        Category parent = getById(parentId);
        if (Objects.isNull(parent)) {
            throw new BusinessException(StatusCode.CATEGORY_NOT_EXIST);
        }
        return parentId;
    }
}
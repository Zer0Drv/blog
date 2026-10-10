package com.zer0drv.blog.admin.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zer0drv.blog.admin.domain.SensitiveWord;
import com.zer0drv.blog.admin.vo.SensitiveWordVO;
import com.zer0drv.blog.common.response.PageResult;
import com.zer0drv.blog.common.sensitive.SensitiveWordChecker;

/**
 * 敏感词库（M5）。命中规则：忽略大小写包含。
 * 命中判定面继承 common 的 SensitiveWordChecker，供 comment 等下游模块单向依赖。
 *
 * @author Yoruhaki
 */
public interface SensitiveWordService extends IService<SensitiveWord>, SensitiveWordChecker {

    /**
     * 敏感词分页（keyword 模糊匹配 word，id 倒序）
     */
    PageResult<SensitiveWordVO> page(long page, long size, String keyword);

    /**
     * 新增敏感词（trim 后落库；重复词静默成功，不抛错）
     */
    void add(String word);

    /**
     * 删除敏感词（逻辑删除）
     */
    void delete(Long id);
}

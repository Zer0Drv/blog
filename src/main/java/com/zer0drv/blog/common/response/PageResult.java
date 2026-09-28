package com.zer0drv.blog.common.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应体
 *
 * @author Yoruhaki
 */
@Data
@NoArgsConstructor
public class PageResult<T> {

    /**
     * 当前页记录
     */
    private List<T> records;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页码（从 1 开始）
     */
    private long page;

    /**
     * 每页大小
     */
    private long size;

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }
}
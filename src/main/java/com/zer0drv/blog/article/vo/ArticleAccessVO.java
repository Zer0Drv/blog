package com.zer0drv.blog.article.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 口令校验通过的访问凭据（issue #15）：opaque token，Redis 存「文章 id + token」键，
 * TTL 内对同一篇文章的详情访问携带即放行（请求头 X-Article-Access-Token）。
 * token 为 122 位随机 UUID，爆破不可行；限流只需守在口令校验端点。
 *
 * @author Yoruhaki
 */
@Data
@AllArgsConstructor
public class ArticleAccessVO {

    /**
     * 访问令牌（前端存 sessionStorage，随详情请求头携带）
     */
    private String accessToken;

    /**
     * 有效期（秒）
     */
    private long expiresIn;
}

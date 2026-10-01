package com.zer0drv.blog.config;

import com.zer0drv.blog.common.util.IpUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 代理头信任开关装配（#4）：把 blog.security.trust-proxy-headers 注入 IpUtils。
 * 默认 false：客户端 IP 一律取 remoteAddr，伪造 X-Forwarded-For 不再绕过图形验证码与频率限制；
 * 仅当应用部署在可信反向代理后才可开启（并建议配合 server.forward-headers-strategy: framework）。
 *
 * @author Yoruhaki
 */
@Configuration
public class TrustProxyConfig {

    public TrustProxyConfig(@Value("${blog.security.trust-proxy-headers:false}") boolean trustProxyHeaders) {
        IpUtils.setTrustProxyHeaders(trustProxyHeaders);
    }
}

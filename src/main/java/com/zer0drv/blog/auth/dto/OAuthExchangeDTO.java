package com.zer0drv.blog.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * OAuth 一次性换码入参（blog-ui#13 契约第 2 条）：POST /auth/oauth/exchange
 *
 * @author Yoruhaki
 */
@Data
public class OAuthExchangeDTO {

    @NotBlank(message = "code 不能为空")
    private String code;
}

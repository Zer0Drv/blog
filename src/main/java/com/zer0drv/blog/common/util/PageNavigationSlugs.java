package com.zer0drv.blog.common.util;

import com.zer0drv.blog.common.exception.BusinessException;
import com.zer0drv.blog.common.response.StatusCode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** 站点导航配置只接受至多 20 个唯一页面 slug，不接受任意 URL 或 HTML。 */
public final class PageNavigationSlugs {
    public static final String CONFIG_KEY = "site.page_navigation";
    private static final ObjectMapper JSON = new ObjectMapper();
    private PageNavigationSlugs() {}

    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            if (json.length() > 5000) throw new IllegalArgumentException();
            var array = JSON.readTree(json);
            if (!array.isArray() || array.size() > 20) throw new IllegalArgumentException();
            List<String> result = new ArrayList<>();
            for (var node : array) {
                if (!node.isString() || node.asString().length() > 200
                        || !node.asString().matches("[a-z0-9]+(-[a-z0-9]+)*")
                        || result.contains(node.asString())) throw new IllegalArgumentException();
                result.add(node.asString());
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new BusinessException(StatusCode.PARAM_INVALID.getCode(), "页面导航需为至多 20 个唯一 slug 的 JSON 数组");
        }
    }
}

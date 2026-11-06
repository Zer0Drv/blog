package com.zer0drv.blog.common.util;

public final class UrlPaths {
    private UrlPaths() {}

    /** Trim only the trailing path separators before joining a URL to a known path. */
    public static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}

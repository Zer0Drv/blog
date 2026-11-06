package com.zer0drv.blog.common.util;

/** Lowercase ASCII alphanumeric segments separated by single hyphens. */
public final class SlugSyntax {
    public static final int MAX_LENGTH = 200;
    // At most 99 separators can fit in 200 characters. No segment can consume a separator.
    public static final String REGEX = "[a-z0-9]++(?:-[a-z0-9]++){0,99}+";

    private SlugSyntax() {}
}

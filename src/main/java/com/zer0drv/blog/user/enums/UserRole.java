package com.zer0drv.blog.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 用户角色：ADMIN=博主/管理员；AUTHOR=被授权发文者；USER=注册读者。
 *
 * @author Yoruhaki
 */
@Getter
@RequiredArgsConstructor
public enum UserRole {
    ADMIN("管理员"),
    AUTHOR("作者"),
    USER("读者");

    private final String desc;

    public static UserRole of(String code) {
        for (UserRole value : UserRole.values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid user role code: " + code);
    }

    public static boolean isValid(String code) {
        for (UserRole value : UserRole.values()) {
            if (value.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}

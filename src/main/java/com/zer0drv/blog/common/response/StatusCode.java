package com.zer0drv.blog.common.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * @author Yoruhaki
 */
@RequiredArgsConstructor
@Getter
public enum StatusCode {

    OK("200", "success"),
    FAIL("40000", "fail"),
    USER_NOT_EXIST("40001", "用户不存在"),
    USER_PASSWORD_ERROR("40002", "用户名或密码错误"),
    USER_CREATE_FAILED("40003", "用户创建失败"),
    USER_UPDATE_FAILED("40004", "用户更新失败"),
    USER_DELETE_FAILED("40005", "用户删除失败"),
    ROLE_CHOICE_ERROR("40006", "角色选择错误"),
    USER_NOT_EXIST_OR_DELETED("40010", "用户不存在或已删除"),
    LOGIN_STATUS_INVALID("40011", "登录状态无效，请重新登录"),
    PASSWORD_NOT_MATCH("40012", "密码不匹配"),
    NO_RESOURCE_FOUND("40013", "资源不存在"),
    HTTP_REQUEST_METHOD_NOT_SUPPORTED("40015", "HTTP请求方法不支持"),
    EMAIL_CODE_INVALID("40018", "邮箱验证码错误或已过期"),
    EMAIL_CODE_TOO_FREQUENT("40019", "验证码发送过于频繁，请稍后再试"),
    USERNAME_EXISTS("40020", "用户名已存在"),
    EMAIL_EXISTS("40021", "邮箱已被注册"),
    USER_BANNED("40022", "账号已被封禁，请联系管理员"),
    PARAM_INVALID("40023", "参数校验失败"),
    ARTICLE_NOT_EXIST("40030", "文章不存在"),
    CATEGORY_NOT_EXIST("40031", "分类不存在"),
    TAG_NOT_EXIST("40032", "标签不存在"),
    ARTICLE_STATUS_INVALID("40033", "文章状态不合法"),
    EDITOR_TYPE_INVALID("40034", "编辑器类型不合法"),
    TAG_EXISTS("40035", "标签已存在"),
    CATEGORY_HAS_CHILDREN("40036", "该分类存在子分类，无法删除"),
    CATEGORY_HAS_ARTICLES("40037", "该分类下存在文章，无法删除"),
    FILE_TYPE_NOT_ALLOWED("40038", "仅支持 jpg/png/gif/webp 格式的图片"),
    FILE_TOO_LARGE("40039", "文件大小不能超过 5MB"),
    FILE_UPLOAD_FAILED("40040", "文件上传失败"),
    COMMENT_NOT_EXIST("40041", "评论不存在"),
    ARTICLE_NOT_PUBLISHED("40042", "文章未发布，无法评论"),
    COMMENT_CONTENT_INVALID("40043", "评论内容不能为空且不能超过 1000 个字符"),
    FOLLOW_SELF_INVALID("40050", "不能关注自己"),
    USER_NOT_FOLLOWED("40051", "尚未关注该用户"),
    MESSAGE_CONTENT_INVALID("40052", "消息内容不能为空且不能超过 1000 个字符"),
    MESSAGE_PEER_INVALID("40053", "不能选择自己为私信对象"),
    MESSAGE_SENSITIVE_HIT("40060", "内容包含敏感词，发送失败"),
    CANNOT_OPERATE_ADMIN("40061", "不能对管理员账号执行该操作"),
    CANNOT_OPERATE_SELF("40062", "不能对自己执行该操作"),
    SENSITIVE_WORD_EXISTS("40063", "敏感词已存在"),
    OAUTH_USER_INFO_INVALID("40064", "OAuth 用户信息无效"),
    EMAIL_NOT_REGISTERED("40065", "该邮箱未注册"),
    // 40050 已被 FOLLOW_SELF_INVALID 占用，取下一个空闲码
    CAPTCHA_REQUIRED("40066", "操作过于频繁，请完成图形验证"),
    ARTICLE_VERSION_NOT_EXIST("40067", "文章版本不存在"),
    ATTACHMENT_NOT_EXIST("40069", "附件不存在"),
    ATTACHMENT_GROUP_NOT_EXIST("40070", "附件分组不存在"),
    ATTACHMENT_GROUP_HAS_ITEMS("40071", "分组内仍有附件，无法删除"),
    NOT_AUTHOR("40301", "仅作者本人或管理员可操作"),
    INTERNAL_SERVER_ERROR("50000", "服务器错误");

    /**
     * 状态码
     */
    private final String code;

    /**
     * 状态码对应的消息
     */
    private final String message;
}
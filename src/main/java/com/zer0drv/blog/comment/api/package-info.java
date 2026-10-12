/**
 * 评论域对外契约包（端口）：外模块读写评论数据的唯一入口（issue #34 第一步）。
 *
 * <p>与 {@code article.api} 同款模式：窄契约、零 MyBatis-Plus 类型，由拥有数据的
 * comment 模块自己实现，外模块单测直接 mock 本包接口即可，无需 MP 元数据初始化。
 * 评论实体 / CommentStatus 存储形态 / 逻辑删除 SQL 等实现细节留在 comment 内部包，
 * 不出现在本包签名中。
 */
package com.zer0drv.blog.comment.api;

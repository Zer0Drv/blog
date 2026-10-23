package com.zer0drv.blog.comment.api;

public interface CommentNotificationState {
    String state(Long commentId);
    void removed(Long commentId, boolean administrator);
}

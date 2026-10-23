package com.zer0drv.blog.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import com.zer0drv.blog.admin.service.AdminCommentService;
import com.zer0drv.blog.social.service.NotificationService;
import com.zer0drv.blog.social.service.NotificationIntent;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationPrivacyIntegrationTests extends IntegrationTestSupport {
    @Autowired AdminCommentService comments;
    @Autowired NotificationService notifications;

    @Test void historicalSnapshotsAreHiddenAndRemovalKeepsReadStateWhileRetractionIsIdempotent() throws Exception {
        String username=unique("notify_user_"); long user=seedUser(username,"用户","USER");
        String adminName=unique("notify_admin_"); long admin=seedUser(adminName,"管理员","ADMIN");
        long article=seedArticle(admin,unique("notice_article_"),"PUBLISHED");
        jdbcTemplate.update("INSERT INTO `comment`(article_id,user_id,parent_id,content,status) VALUES(?,?,0,'private snapshot','NORMAL')",article,user);
        long comment=jdbcTemplate.queryForObject("SELECT MAX(id) FROM `comment`",Long.class);
        jdbcTemplate.update("INSERT INTO notification(user_id,type,article_id,comment_id,summary,read_flag) VALUES(?,'COMMENT_REPLY',?,?,'historical secret',1)",user,article,comment);
        long notification=jdbcTemplate.queryForObject("SELECT MAX(id) FROM notification",Long.class);
        String bearer=bearerOf(username), adminBearer=bearerOf(adminName);
        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.data.records[0].summary").value("收到评论互动，请前往文章查看"));
        comments.forceDelete(comment);
        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.data.records[0].summary").value("该评论已被管理员移除"))
                .andExpect(jsonPath("$.data.records[0].readFlag").value(1))
                .andExpect(jsonPath("$.data.records[0].articleId").doesNotExist());
        // A late delivery cannot write the old text back or expose it through a push payload.
        notifications.notify(NotificationIntent.commentReply(user,admin,article,comment,"late secret"));
        assertEquals("",jdbcTemplate.queryForObject("SELECT summary FROM notification WHERE user_id=? ORDER BY id DESC LIMIT 1",String.class,user));
        mockMvc.perform(delete("/admin/notifications/{id}",notification).header(HttpHeaders.AUTHORIZATION,bearer)).andExpect(status().isForbidden());
        for(int i=0;i<2;i++) mockMvc.perform(delete("/admin/notifications/{id}",notification).header(HttpHeaders.AUTHORIZATION,adminBearer)).andExpect(jsonPath("$.code").value("200"));
        assertEquals(1,jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_retraction_audit WHERE notification_id=?",Integer.class,notification));
        jdbcTemplate.update("UPDATE article SET visibility='PRIVATE' WHERE id=?",article);
        mockMvc.perform(get("/notifications").header(HttpHeaders.AUTHORIZATION,bearer))
                .andExpect(jsonPath("$.data.records[0].summary").value("相关内容暂不可访问"));
    }
}

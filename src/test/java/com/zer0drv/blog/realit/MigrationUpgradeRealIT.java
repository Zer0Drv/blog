package com.zer0drv.blog.realit;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL upgrade with retained data, not just empty-schema DDL execution. */
class MigrationUpgradeRealIT {
    @ParameterizedTest
    @ValueSource(ints={13,15})
    void retainedContentSurvivesUpgradeToCurrentSchema(int baseline) throws Exception {
        assertEquals("true",System.getProperty("blog.real-it.run"));
        String url=System.getenv().getOrDefault("REAL_IT_MYSQL_URL","jdbc:mysql://127.0.0.1:23307/blog_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8");
        var endpoint=RealInfrastructureSafety.mysql(url); // validate before opening any connection
        String password=System.getenv().getOrDefault("REAL_IT_DB_PASSWORD","blog-it-password");
        String schema="blog_it_upgrade_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(schema.matches("blog_it_upgrade_[a-f0-9]{32}"));
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"root",password));
        admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        try {
            String upgradedUrl="jdbc:mysql://"+endpoint.getHost()+":"+endpoint.getPort()+"/"+schema+(endpoint.getRawQuery()==null?"":"?"+endpoint.getRawQuery());
            var source=new DriverManagerDataSource(upgradedUrl,"root",password);
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion(String.valueOf(baseline))).cleanDisabled(true).load().migrate();
            var db=new JdbcTemplate(source);
            String content="历史正文 ![](/uploads/202001/1234567890abcdef1234567890abcdef.png)";
            String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Upgrade-only-password");
            db.update("INSERT INTO `user`(id,username,password,email,nickname,role) VALUES(700001,'upgrade_user',?,'upgrade@real-it.invalid','升级作者','AUTHOR')",hash);
            db.update("INSERT INTO category(id,name) VALUES(700003,'迁移分类')");
            db.update("INSERT INTO tag(id,name) VALUES(700004,'迁移标签')");
            db.update("INSERT INTO article(id,title,slug,content,content_text,editor_type,cover,author_id,category_id,status,visibility) VALUES(700002,'历史私密草稿','upgrade-private',?,?,'MARKDOWN','/uploads/cover.png',700001,700003,'DRAFT','PRIVATE')",content,content);
            db.update("INSERT INTO article_tag(article_id,tag_id) VALUES(700002,700004)");
            db.update("INSERT INTO article_version(id,article_id,version,title,content,editor_type) VALUES(700005,700002,3,'历史版本',?,'MARKDOWN')",content);
            db.update("INSERT INTO article(id,title,slug,content,editor_type,author_id,deleted) VALUES(700007,'回收站文章','upgrade-trash',?,'MARKDOWN',700001,1)",content);
            db.update("INSERT INTO attachment(id,user_id,url,object_key,storage,filename,size_bytes,deleted) VALUES(700006,700001,'/uploads/202001/1234567890abcdef1234567890abcdef.png','202001/1234567890abcdef1234567890abcdef.png','LOCAL','旧图片.png',1024,1)");
            db.update("INSERT INTO comment(article_id,user_id,content,deleted) VALUES(700002,700001,'回收站评论',1)");
            db.update("INSERT INTO custom_page(slug,title,content) VALUES('upgrade-page','历史页面',?)",content);
            db.update("UPDATE site_config SET config_value='迁移前页脚' WHERE config_key='site.footer'");
            db.update("INSERT INTO article(id,title,slug,content,editor_type,author_id,status,publish_time) VALUES(700011,'旧公开文章','upgrade-public',?,'MARKDOWN',700001,'PUBLISHED',DATE_SUB(NOW(),INTERVAL 1 DAY))",content);
            db.update("INSERT INTO article(id,title,slug,content,editor_type,author_id,status,publish_time) VALUES(700012,'旧定时文章','upgrade-scheduled',?,'MARKDOWN',700001,'PUBLISHED',DATE_ADD(NOW(),INTERVAL 1 DAY))",content);
            if(baseline>=15) {
                db.update("INSERT INTO sys_config(config_key,config_value,value_type,group_name,description,validation_rule,hot_reload) VALUES('blog.upload-daily-limit','42','INT','上传','配额','{}',true)");
                db.update("INSERT INTO sys_config_audit(config_key,action,old_value,new_value,old_source,new_source,operator_id) VALUES('blog.upload-daily-limit','SET','100','42','YAML','DATABASE',700001)");
                db.update("INSERT INTO subscription_publication(article_id,due_at,state) SELECT id,publish_time,CASE WHEN id=700011 THEN 'DONE' ELSE 'PENDING' END FROM article WHERE id IN(700011,700012)");
            }
            var current=Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load();
            var result=current.migrate();assertTrue(result.migrationsExecuted>0);assertTrue(current.validateWithResult().validationSuccessful);
            assertEquals(content,db.queryForObject("SELECT content FROM article WHERE id=700002",String.class));
            assertEquals(hash,db.queryForObject("SELECT password FROM `user` WHERE id=700001",String.class));
            assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM article WHERE id=700002",String.class));
            assertEquals(700003L,db.queryForObject("SELECT category_id FROM article WHERE id=700002",Long.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM article_tag WHERE article_id=700002 AND tag_id=700004",Integer.class));
            assertEquals(content,db.queryForObject("SELECT content FROM article_version WHERE article_id=700002 AND version=3",String.class));
            assertEquals(1,db.queryForObject("SELECT deleted FROM article WHERE id=700007",Integer.class));
            assertEquals("202001/1234567890abcdef1234567890abcdef.png",db.queryForObject("SELECT object_key FROM attachment WHERE id=700006 AND deleted=1",String.class));
            assertEquals("回收站评论",db.queryForObject("SELECT content FROM comment WHERE article_id=700002 AND deleted=1",String.class));
            assertEquals(content,db.queryForObject("SELECT content FROM custom_page WHERE slug='upgrade-page'",String.class));
            assertEquals("迁移前页脚",db.queryForObject("SELECT config_value FROM site_config WHERE config_key='site.footer'",String.class));
            assertEquals("DONE",db.queryForObject("SELECT state FROM subscription_publication WHERE article_id=700011",String.class));
            assertEquals("PENDING",db.queryForObject("SELECT state FROM subscription_publication WHERE article_id=700012",String.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM attachment_cleanup_lock WHERE id=1",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM attachment_cleanup",Integer.class));
            assertEquals(0L,db.queryForObject("SELECT edit_revision FROM article WHERE id=700002",Long.class));
            assertEquals(0L,db.queryForObject("SELECT edit_revision FROM article WHERE id=700011",Long.class));
            assertEquals("PUBLISHED",db.queryForObject("SELECT status FROM article WHERE id=700011",String.class));
            assertEquals(content,db.queryForObject("SELECT content FROM article WHERE id=700011",String.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM article_working_copy",Integer.class));
            if(baseline>=15) {
                assertEquals("42",db.queryForObject("SELECT config_value FROM sys_config WHERE config_key='blog.upload-daily-limit'",String.class));
                assertEquals("42",db.queryForObject("SELECT new_value FROM sys_config_audit WHERE operator_id=700001",String.class));
            }
            assertEquals(0,current.migrate().migrationsExecuted,"Repeated migration must be a no-op");
            Path evidence=Path.of("target","real-it-reports","upgrade-v"+baseline+".txt");Files.createDirectories(evidence.getParent());
            Files.writeString(evidence,"baseline="+baseline+"\ncurrent="+current.info().current().getVersion()+"\nmigrationsExecuted="+result.migrationsExecuted+"\nretainedData=verified\nrepeatMigration=no-op\n");
        } finally {
            // Only a UUID schema created by this method is dropped; never clean the application schema.
            assertTrue(schema.matches("blog_it_upgrade_[a-f0-9]{32}"));
            admin.execute("DROP DATABASE `"+schema+"`");
        }
    }
}

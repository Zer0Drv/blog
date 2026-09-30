package com.zer0drv.blog.it;

import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口级集成测试基类：@SpringBootTest + MockMvc 打真实 HTTP 路由
 * （安全链 / 参数校验 / 序列化 / 权限全部真实生效），H2 内存库替代 MySQL。
 * 测试环境无 Redis / 邮件服务：StringRedisTemplate 以深桩 Mock 顶替（读一律返回 null，
 * 即验证码未达频率阈值直接放行、JWT 黑名单未命中），用户一律 SQL 预置（注册走邮箱验证码不可测）。
 * 隔离方式：每个测试方法自建数据 + 全局自增序号保证用户名 / 标题唯一，不依赖事务回滚。
 *
 * @author Yoruhaki
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestSupport.RedisStubConfiguration.class)
public abstract class IntegrationTestSupport {

    /**
     * 测试用户统一明文密码
     */
    protected static final String TEST_PASSWORD = "It-passw0rd";

    /**
     * 上述明文对应的 BCrypt 哈希（全 JVM 只算一次，避免慢哈希拖慢套件）
     */
    protected static final String TEST_PASSWORD_HASH = new BCryptPasswordEncoder().encode(TEST_PASSWORD);

    /**
     * 唯一名序号：共享 H2 库（DB_CLOSE_DELAY=-1）下保证各测试数据互不干扰
     */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Boot 4 为 Jackson 3（tools.jackson），直接注入容器自动装配的 ObjectMapper
     */
    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * Redis 顶替配置：深桩让所有读操作返回 null、写操作静默成功。
     * 覆盖：验证码频率计数（未达阈值放行）、JWT 黑名单校验（未命中）、邮箱验证码（本套件不触达）。
     */
    @TestConfiguration
    static class RedisStubConfiguration {

        @Bean
        @Primary
        StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
        }
    }

    /**
     * 生成全局唯一名称（用于用户名 / 标题 / 敏感词等）
     */
    protected static String unique(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    /**
     * Authorization: Bearer 请求头
     */
    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    /**
     * 预置用户并返回 id（邮箱取 username@it.local 满足 uk_email 唯一约束）
     */
    protected long seedUser(String username, String nickname, String role) {
        jdbcTemplate.update(
                "INSERT INTO `user` (username, password, email, nickname, role, status) VALUES (?,?,?,?,?,0)",
                username, TEST_PASSWORD_HASH, username + "@it.local", nickname, role);
        return jdbcTemplate.queryForObject("SELECT id FROM `user` WHERE username = ?", Long.class, username);
    }

    /**
     * 预置根分类并返回 id
     */
    protected long seedCategory(String name) {
        jdbcTemplate.update("INSERT INTO category (name, parent_id, sort) VALUES (?,0,0)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    /**
     * 预置标签并返回 id
     */
    protected long seedTag(String name) {
        jdbcTemplate.update("INSERT INTO tag (name) VALUES (?)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM tag WHERE name = ?", Long.class, name);
    }

    /**
     * 预置文章并返回 id；status 为 PUBLISHED 时写发布时间
     * （截断到秒：H2/MySQL DATETIME 精度为秒且会四舍五入，带纳秒的时间可能进位到未来，
     * 使 P0 的 publish_time <= now 可见性谓词把刚预置的文章误判为「定时中」）
     */
    protected long seedArticle(long authorId, String title, String status) {
        Timestamp publishTime = "PUBLISHED".equals(status)
                ? Timestamp.valueOf(LocalDateTime.now().withNano(0)) : null;
        jdbcTemplate.update(
                "INSERT INTO article (title, summary, content, editor_type, cover, author_id, status, publish_time)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                title, title, title + "正文", "MARKDOWN", "", authorId, status, publishTime);
        return jdbcTemplate.queryForObject("SELECT id FROM article WHERE title = ?", Long.class, title);
    }

    /**
     * 预置敏感词
     */
    protected void seedSensitiveWord(String word) {
        jdbcTemplate.update("INSERT INTO sensitive_word (word) VALUES (?)", word);
    }

    /**
     * 真实走 POST /auth/login 换取 JWT（安全链与签发逻辑全程真实生效）
     */
    protected String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("access_token").asString();
        if (Objects.isNull(token) || token.isBlank()) {
            throw new IllegalStateException("登录未返回 access_token");
        }
        return token;
    }

    /**
     * 以指定用户身份登录并返回 Bearer 头值
     */
    protected String bearerOf(String username) throws Exception {
        return bearer(login(username, TEST_PASSWORD));
    }

    /**
     * 构造 Authorization 头
     */
    protected static HttpHeaders authHeaders(String bearerValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerValue);
        return headers;
    }
}

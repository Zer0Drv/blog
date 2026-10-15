package com.zer0drv.blog.realit;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import java.net.URI;
import java.util.Set;

/** Runs before Flyway / MinIO startup, so a misconfigured target cannot be migrated or reconfigured. */
public class RealInfrastructureSafety implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var env=context.getEnvironment();
        if (!"true".equals(env.getProperty("blog.real-it.run")))
            throw new IllegalStateException("Use the explicit -Preal-it Maven profile");
        String jdbc=env.getRequiredProperty("spring.datasource.url");
        mysql(jdbc);
        if (!Set.of("127.0.0.1","localhost").contains(env.getProperty("spring.data.redis.host"))
                || env.getProperty("spring.data.redis.port",Integer.class,0)<20000
                || env.getProperty("spring.data.redis.database",Integer.class,-1)!=15)
            throw new IllegalStateException("Real IT requires a dedicated loopback Redis port and database 15");
        local(URI.create(env.getRequiredProperty("blog.minio.endpoint")));
        if (!env.getRequiredProperty("blog.minio.bucket").matches("blog-it-[a-f0-9]{32}"))
            throw new IllegalStateException("Real IT bucket must be a generated per-run bucket");
        if (!"never".equals(env.getProperty("spring.sql.init.mode"))
                || !env.getProperty("spring.flyway.enabled",Boolean.class,false)
                || !env.getProperty("blog.minio.enabled",Boolean.class,false)
                || !env.getProperty("blog.search.fulltext-enabled",Boolean.class,false))
            throw new IllegalStateException("Real IT must exercise Flyway, MinIO and fulltext without H2 schema init");
    }
    static URI mysql(String jdbc) {
        if (!jdbc.startsWith("jdbc:mysql:")) throw new IllegalStateException("Real IT requires MySQL");
        URI mysql=URI.create(jdbc.substring(5));
        local(mysql);
        if (!mysql.getPath().matches("/blog_it(?:_[a-z0-9]+)?"))
            throw new IllegalStateException("Real IT database must use the isolated blog_it prefix");
        return mysql;
    }
    private static void local(URI uri) {
        if (!Set.of("127.0.0.1","localhost").contains(uri.getHost()) || uri.getPort()<20000 || uri.getPort()>65535)
            throw new IllegalStateException("Real IT endpoints must use dedicated loopback ports >= 20000");
    }
}

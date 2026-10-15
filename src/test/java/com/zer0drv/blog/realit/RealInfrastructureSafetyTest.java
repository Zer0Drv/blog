package com.zer0drv.blog.realit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RealInfrastructureSafetyTest {
    private Map<String,Object> safe() {
        return new HashMap<>(Map.ofEntries(Map.entry("blog.real-it.run","true"),
                Map.entry("spring.datasource.url","jdbc:mysql://127.0.0.1:23307/blog_it"),
                Map.entry("spring.data.redis.host","127.0.0.1"),Map.entry("spring.data.redis.port","26379"),Map.entry("spring.data.redis.database","15"),
                Map.entry("blog.minio.endpoint","http://127.0.0.1:29000"),Map.entry("blog.minio.bucket","blog-it-1234567890abcdef1234567890abcdef"),
                Map.entry("spring.sql.init.mode","never"),Map.entry("spring.flyway.enabled","true"),Map.entry("blog.minio.enabled","true"),Map.entry("blog.search.fulltext-enabled","true")));
    }
    private void initialize(Map<String,Object> values) {
        try(var context=new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("real-it-safety-test",values));
            new RealInfrastructureSafety().initialize(context);
        }
    }
    @Test void acceptsOnlyExplicitIsolatedConfiguration() { assertDoesNotThrow(()->initialize(safe())); }
    @ParameterizedTest
    @CsvSource({"spring.datasource.url,jdbc:h2:mem:fake","spring.datasource.url,jdbc:mysql://db.example:23307/blog_it",
            "spring.datasource.url,jdbc:mysql://127.0.0.1:23307/blog_dev","spring.datasource.url,jdbc:mysql://127.0.0.1:3307/blog_it",
            "spring.data.redis.port,6379","spring.data.redis.database,0","blog.minio.endpoint,http://127.0.0.1:9000",
            "blog.minio.bucket,blog-images","spring.sql.init.mode,always","spring.flyway.enabled,false","blog.real-it.run,false"})
    void rejectsUnsafeOrFakeDependenciesBeforeStartup(String key,String value) {
        var settings=safe();settings.put(key,value);
        assertThrows(IllegalStateException.class,()->initialize(settings));
    }
}

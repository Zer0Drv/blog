package com.zer0drv.blog.realit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class BrowserE2EServerTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={"production", "../blog-images", "0000000000000000000000000000000Z"})
    void refusesInvalidRunIdentifiers(String run) {
        assertThrows(IllegalArgumentException.class, () -> BrowserE2EServer.settings(run));
    }

    @Test void refusesArgumentsBeforeStartingTheApplication() {
        assertThrows(IllegalArgumentException.class, () -> BrowserE2EServer.main(new String[]{"--server.port=8082"}));
    }

    @Test void fixedConfigurationPassesExistingInfrastructureSafetyAndBindsOnlyIsolatedHttpPort() {
        var properties = new HashMap<String,Object>();
        for (String argument : BrowserE2EServer.settings("1234567890abcdef1234567890abcdef")) {
            String[] pair=argument.substring(2).split("=",2);
            properties.put(pair[0],pair[1]);
        }
        assertEquals("127.0.0.1",properties.get("server.address"));
        assertEquals("28082",properties.get("server.port"));
        assertEquals("false",properties.get("blog.admin.bootstrap-enabled"));
        try(var context=new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("browser-fixture",properties));
            assertDoesNotThrow(() -> new RealInfrastructureSafety().initialize(context));
        }
    }
}

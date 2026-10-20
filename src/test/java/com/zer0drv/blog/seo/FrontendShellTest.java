package com.zer0drv.blog.seo;

import com.sun.net.httpserver.HttpServer;
import com.zer0drv.blog.seo.service.FrontendShell;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FrontendShellTest {
    @Test
    void readsCurrentShellButRejectsRedirectErrorsOversizeAndNonApplication() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] bytes = switch (path) {
                case "/large" -> new byte[1024 * 1024 + 1];
                case "/invalid" -> "<html>Not an app</html>".getBytes(StandardCharsets.UTF_8);
                default -> "<html><div id='app'></div><script src='/assets/current.js'></script></html>"
                        .getBytes(StandardCharsets.UTF_8);
            };
            int status = path.equals("/redirect") ? 302 : path.equals("/error") ? 404 : 200;
            exchange.getResponseHeaders().add("Location", "/");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(new FrontendShell(base + "/").load().contains("/assets/current.js"));
            for (String path : new String[]{"/large", "/invalid", "/redirect", "/error"}) {
                assertThrows(IOException.class, () -> new FrontendShell(base + path).load());
            }
            for (String url : new String[]{"", "file:///etc/passwd", "http://user:pass@localhost/"}) {
                assertThrows(IOException.class, () -> new FrontendShell(url).load());
            }
        } finally {
            server.stop(0);
        }
    }
}

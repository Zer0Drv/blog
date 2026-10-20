package com.zer0drv.blog.seo.controller;

import com.zer0drv.blog.seo.service.ArticleHtmlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
public class ArticleHtmlController {
    private final ArticleHtmlService html;

    @GetMapping(value = "/article/{key:[a-z0-9-]+}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> article(@PathVariable String key) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html.render(key));
        } catch (IOException exception) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                    .header("Retry-After", "30").body("Frontend temporarily unavailable");
        }
    }
}

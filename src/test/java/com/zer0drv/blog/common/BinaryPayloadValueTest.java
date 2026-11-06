package com.zer0drv.blog.common;

import com.zer0drv.blog.article.vo.ArticleExportVO;
import com.zer0drv.blog.attachment.service.AttachmentCleanupService.Preview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BinaryPayloadValueTest {
    @Test
    void articleExportsCompareTheirBytes() {
        var first = new ArticleExportVO(new byte[]{1, 2}, "article", "md");
        var same = new ArticleExportVO(new byte[]{1, 2}, "article", "md");
        var different = new ArticleExportVO(new byte[]{1, 3}, "article", "md");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
    }

    @Test
    void attachmentPreviewsCompareTheirBytes() {
        var first = new Preview(new byte[]{1, 2}, "image.png", "image/png");
        var same = new Preview(new byte[]{1, 2}, "image.png", "image/png");
        var different = new Preview(new byte[]{1, 3}, "image.png", "image/png");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
    }
}

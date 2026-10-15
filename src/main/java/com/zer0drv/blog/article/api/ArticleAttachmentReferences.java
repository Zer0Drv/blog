package com.zer0drv.blog.article.api;

import java.util.Set;

/** All retained article, version and Redis autosave references, including trash/private/drafts. */
public interface ArticleAttachmentReferences {
    Set<String> retainedAttachmentFilenames();
}

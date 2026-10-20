package com.zer0drv.blog.realit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class RestoreDrillSnapshotTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"database.sql", "minio.png", "local.png"})
    void anyMissingOrCorruptMemberRejectsTheWholeSnapshot(String name) throws Exception {
        for (String member : RestoreDrillSnapshot.FILES) Files.writeString(root.resolve(member), "original " + member);
        var manifest = RestoreDrillSnapshot.manifest(root);
        RestoreDrillSnapshot.verify(root, manifest);
        Files.writeString(root.resolve(name), "corrupted");
        assertThrows(IOException.class, () -> RestoreDrillSnapshot.verify(root, manifest));
        Files.delete(root.resolve(name));
        assertThrows(IOException.class, () -> RestoreDrillSnapshot.verify(root, manifest));
        Files.writeString(root.resolve(name), "");
        assertThrows(IOException.class, () -> RestoreDrillSnapshot.verify(root, manifest));
    }

    @Test
    void manifestCannotOmitAnAttachmentOrIntroduceAPath() throws Exception {
        for (String member : RestoreDrillSnapshot.FILES) Files.writeString(root.resolve(member), "fixture");
        var manifest = new HashMap<>(RestoreDrillSnapshot.manifest(root));
        manifest.remove("local.png");
        assertThrows(IOException.class, () -> RestoreDrillSnapshot.verify(root, manifest));
        manifest.put("../local.png", "a".repeat(64));
        assertThrows(IOException.class, () -> RestoreDrillSnapshot.verify(root, manifest));
    }

    @Test
    void onlyGeneratedSchemasCanBeCreatedOrDropped() {
        String run = "a".repeat(32);
        assertEquals("blog_it_restore" + run, RestoreDrillSnapshot.schema("restore", run));
        for (String invalid : new String[]{"", "blog", "../x", "A".repeat(32), "a".repeat(31)})
            assertThrows(IllegalArgumentException.class, () -> RestoreDrillSnapshot.schema("restore", invalid));
        assertThrows(IllegalArgumentException.class, () -> RestoreDrillSnapshot.schema("production", run));
        assertThrows(IllegalArgumentException.class, () -> RestoreDrillSnapshot.schema("source", null));
    }
}

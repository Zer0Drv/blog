package com.zer0drv.blog.realit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Test-only, fixed-layout snapshot. This is not a production backup/restore command. */
final class RestoreDrillSnapshot {
    static final Set<String> FILES = Set.of("database.sql", "minio.png", "local.png");

    static String schema(String purpose, String run) {
        if (!Set.of("source", "restore").contains(purpose) || run == null || !run.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("Restore drill requires an owned UUID schema");
        return "blog_it_" + purpose + run;
    }

    static Map<String, String> manifest(Path root) throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (String name : FILES.stream().sorted().toList()) hashes.put(name, hash(file(root, name)));
        return Map.copyOf(hashes);
    }

    static void verify(Path root, Map<String, String> manifest) throws Exception {
        if (!manifest.keySet().equals(FILES)) throw new IOException("Incomplete snapshot manifest");
        // Validate every member before any restore write; never accept a partial database-only restore.
        for (String name : FILES) {
            String expected = manifest.get(name);
            if (expected == null || !expected.matches("[a-f0-9]{64}") || !expected.equals(hash(file(root, name))))
                throw new IOException("Snapshot checksum mismatch: " + name);
        }
    }

    static String hash(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static Path file(Path root, String name) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path file = base.resolve(name);
        if (!FILES.contains(name) || Files.isSymbolicLink(base)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0)
            throw new IOException("Missing or unsafe snapshot member: " + name);
        return file;
    }
}

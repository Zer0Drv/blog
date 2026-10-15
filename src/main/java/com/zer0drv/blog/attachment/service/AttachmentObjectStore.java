package com.zer0drv.blog.attachment.service;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Strict inventory/deletion API; unlike the legacy best-effort upload API, errors propagate. */
@Service
public class AttachmentObjectStore {
    public record ObjectInfo(String storage, String key, long size, Instant modified, String fingerprint) {}
    private final ObjectProvider<MinioClient> clients;
    @Value("${blog.upload-dir:./uploads}") private String uploadDir;
    @Value("${blog.minio.enabled:false}") private boolean minioEnabled;
    @Value("${blog.minio.bucket:blog-images}") private String bucket;
    @Value("${blog.minio.endpoint:http://localhost:9000}") private String endpoint;
    private static final int MAX_OBJECTS = 100_000;

    public AttachmentObjectStore(ObjectProvider<MinioClient> clients) { this.clients = clients; }

    public List<ObjectInfo> inventory() throws Exception {
        List<ObjectInfo> objects = new ArrayList<>();
        Path root = root();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root)) throw new IllegalStateException("上传目录不能是符号链接");
            try (var paths = Files.walk(root, 2)) {
                var iterator = paths.iterator();
                int visited = 0;
                while (iterator.hasNext()) {
                    if (++visited > MAX_OBJECTS * 2) throw new IllegalStateException("目录超过扫描上限");
                    Path path = iterator.next();
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    String key = root.relativize(path).toString().replace('\\', '/');
                    if (!AttachmentObjectKeys.managed(key)) continue;
                    ObjectInfo object = stat("LOCAL", key);
                    if (object != null) objects.add(object);
                    if (objects.size() > MAX_OBJECTS) throw new IllegalStateException("对象超过扫描上限，请分区维护");
                }
            }
        }
        if (minioEnabled) {
            for (var result : client().listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                var item = result.get(); // Iterator / permissions / connectivity failure aborts the entire scan.
                if (!item.isDir() && AttachmentObjectKeys.managed(item.objectName())) {
                    objects.add(new ObjectInfo("MINIO", item.objectName(), item.size(), item.lastModified().toInstant(),
                            fingerprint("MINIO", item.etag() + ":" + item.lastModified().toEpochSecond())));
                    if (objects.size() > MAX_OBJECTS) throw new IllegalStateException("对象超过扫描上限，请分区维护");
                }
            }
        }
        return objects;
    }

    public ObjectInfo stat(String storage, String key) throws Exception {
        validate(key);
        if ("LOCAL".equals(storage)) {
            Path path = localPath(key);
            try {
                var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile()) throw new IllegalStateException("上传对象不是普通文件");
                return new ObjectInfo(storage, key, attrs.size(), attrs.lastModifiedTime().toInstant(),
                        fingerprint(storage, attrs.size() + ":" + attrs.lastModifiedTime() + ":" + attrs.fileKey()));
            } catch (NoSuchFileException missing) { return null; }
        }
        if (!"MINIO".equals(storage) || !minioEnabled) throw new IllegalStateException("对象存储未启用");
        try {
            var stat = client().statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return new ObjectInfo(storage, key, stat.size(), stat.lastModified().toInstant(),
                    fingerprint(storage, stat.etag() + ":" + stat.lastModified().toEpochSecond()));
        } catch (ErrorResponseException error) {
            if ("NoSuchKey".equals(error.errorResponse().code())) return null;
            throw error;
        }
    }

    /** Application UUID keys are immutable. Foreign replacement of an object must restart observation. */
    public boolean delete(ObjectInfo expected) throws Exception {
        var actual = stat(expected.storage(), expected.key());
        if (actual == null) return false;
        if (!actual.fingerprint().equals(expected.fingerprint()) || actual.size() != expected.size())
            throw new IllegalStateException("对象在观察期后发生变化，请重新扫描");
        if ("LOCAL".equals(expected.storage())) return Files.deleteIfExists(localPath(expected.key()));
        client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(expected.key()).build());
        // Versioned buckets may retain historical versions; report logical object bytes, not disk usage.
        return true;
    }

    private Path root() { return Path.of(uploadDir).toAbsolutePath().normalize(); }
    private Path localPath(String key) throws Exception {
        Path root = root();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || Files.isSymbolicLink(root) || Files.isSymbolicLink(target.getParent())
                || Files.isSymbolicLink(target)) throw new IllegalStateException("拒绝符号链接或越界路径");
        if (Files.exists(target.getParent()) && !target.getParent().toRealPath().startsWith(root.toRealPath()))
            throw new IllegalStateException("上传目录解析到根目录之外");
        return target;
    }
    private String fingerprint(String storage, String value) throws Exception {
        String namespace = "LOCAL".equals(storage) ? root().toString() : endpoint + "/" + bucket;
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash) + ":" + value;
    }
    private static void validate(String key) {
        if (!AttachmentObjectKeys.managed(key)) throw new IllegalArgumentException("不受管理的对象名");
    }
    private MinioClient client() {
        var client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("MinIO 客户端不可用");
        return client;
    }
}

package com.smartfms.os;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * OS-level filesystem and system telemetry service.
 * Bridges the Java application with the host Operating System's filesystem,
 * disk FileStore, and JVM/OS resource metrics.
 */
public final class OsStorageService {
    private final Path storageRoot;

    public OsStorageService(Path storageRoot) throws IOException {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.storageRoot);
        Files.createDirectories(this.storageRoot.resolve("users"));
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    public Path ensureUserRoot(long userId) throws IOException {
        Path p = storageRoot.resolve("users").resolve(String.valueOf(userId)).normalize();
        Files.createDirectories(p);
        return p;
    }

    public Path createOsFolder(long userId, String relFolderPath) throws IOException {
        Path userRoot = ensureUserRoot(userId);
        Path target = resolveSafe(userRoot, relFolderPath);
        Files.createDirectories(target);
        return target;
    }

    public void deleteOsDirectoryRecursively(long userId, String relFolderPath) throws IOException {
        Path userRoot = ensureUserRoot(userId);
        Path target = resolveSafe(userRoot, relFolderPath);
        if (Files.exists(target) && !target.equals(userRoot)) {
            try (var walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            }
        }
    }

    public record SavedFileInfo(String storedName, String osRelPath, long sizeBytes, String sha256, String mimeType, String category) {}

    public SavedFileInfo saveFile(long userId, String folderRelPath, String originalName, String clientMime, byte[] data) throws IOException {
        Path userRoot = ensureUserRoot(userId);
        Path targetDir = (folderRelPath == null || folderRelPath.isBlank())
                ? userRoot
                : resolveSafe(userRoot, folderRelPath);
        Files.createDirectories(targetDir);

        String safeOrig = sanitizeFilename(originalName);
        String storedName = UUID.randomUUID().toString().substring(0, 8) + "_" + safeOrig;
        Path targetFile = targetDir.resolve(storedName).normalize();
        if (!targetFile.startsWith(userRoot)) {
            throw new SecurityException("Invalid OS file path");
        }

        Files.write(targetFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String relPath = userRoot.relativize(targetFile).toString().replace('\\', '/');
        String sha256 = sha256Hex(data);
        String probedMime = Files.probeContentType(targetFile);
        String effectiveMime = (probedMime != null && !probedMime.isBlank())
                ? probedMime
                : ((clientMime != null && !clientMime.isBlank()) ? clientMime : "application/octet-stream");
        String category = categorize(safeOrig, effectiveMime);

        return new SavedFileInfo(storedName, relPath, data.length, sha256, effectiveMime, category);
    }

    public String moveFile(long userId, String currentOsRelPath, String targetFolderRelPath, String storedName) throws IOException {
        Path userRoot = ensureUserRoot(userId);
        Path src = resolveSafe(userRoot, currentOsRelPath);
        Path destDir = (targetFolderRelPath == null || targetFolderRelPath.isBlank())
                ? userRoot
                : resolveSafe(userRoot, targetFolderRelPath);
        Files.createDirectories(destDir);
        Path dest = destDir.resolve(storedName).normalize();
        if (!dest.startsWith(userRoot)) {
            throw new SecurityException("Invalid OS destination path");
        }
        if (Files.exists(src) && !src.equals(dest)) {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return userRoot.relativize(dest).toString().replace('\\', '/');
    }

    public byte[] readFile(long userId, String osRelPath) throws IOException {
        Path userRoot = ensureUserRoot(userId);
        Path file = resolveSafe(userRoot, osRelPath);
        if (!Files.exists(file)) {
            throw new NoSuchFileException("File missing on OS filesystem: " + osRelPath);
        }
        return Files.readAllBytes(file);
    }

    public void deleteFile(long userId, String osRelPath) throws IOException {
        Path userRoot = ensureUserRoot(userId);
        Path file = resolveSafe(userRoot, osRelPath);
        Files.deleteIfExists(file);
    }

    public Map<String, Object> getOsTelemetry() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            FileStore store = Files.getFileStore(storageRoot);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            long used = total - store.getUnallocatedSpace();
            m.put("diskName", store.name());
            m.put("diskType", store.type());
            m.put("diskTotalBytes", total);
            m.put("diskUsableBytes", usable);
            m.put("diskUsedBytes", used);
        } catch (IOException e) {
            m.put("diskError", e.getMessage());
        }

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        Runtime rt = Runtime.getRuntime();
        m.put("osName", System.getProperty("os.name"));
        m.put("osVersion", System.getProperty("os.version"));
        m.put("osArch", System.getProperty("os.arch"));
        m.put("availableProcessors", os.getAvailableProcessors());
        m.put("systemLoadAverage", os.getSystemLoadAverage());
        m.put("jvmMaxMemoryBytes", rt.maxMemory());
        m.put("jvmTotalMemoryBytes", rt.totalMemory());
        m.put("jvmFreeMemoryBytes", rt.freeMemory());
        m.put("storageRootPath", storageRoot.toString());
        m.put("javaVersion", System.getProperty("java.version"));
        return m;
    }

    private Path resolveSafe(Path userRoot, String relPath) {
        if (relPath == null || relPath.isBlank()) return userRoot;
        Path resolved = userRoot.resolve(relPath).normalize();
        if (!resolved.startsWith(userRoot)) {
            throw new SecurityException("Path traversal blocked");
        }
        return resolved;
    }

    public static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public static String sanitizeFolderName(String name) {
        if (name == null || name.isBlank()) return "Folder";
        String clean = name.replaceAll("[\\\\/:*?\"<>|.]", "_").trim();
        return clean.isEmpty() ? "Folder" : clean;
    }

    public static String categorize(String filename, String mimeType) {
        String lower = filename.toLowerCase(Locale.ROOT);
        String ext = "";
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) ext = lower.substring(dot + 1);

        if (Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "tiff").contains(ext)
                || (mimeType != null && mimeType.startsWith("image/"))) {
            return "Images";
        }
        if (Set.of("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv").contains(ext)
                || (mimeType != null && mimeType.startsWith("video/"))) {
            return "Videos";
        }
        if (Set.of("mp3", "wav", "ogg", "flac", "aac", "m4a").contains(ext)
                || (mimeType != null && mimeType.startsWith("audio/"))) {
            return "Audio";
        }
        if (Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt").contains(ext)) {
            return "Documents";
        }
        if (Set.of("zip", "rar", "7z", "tar", "gz", "bz2", "xz").contains(ext)) {
            return "Archives";
        }
        if (Set.of("java", "py", "js", "ts", "jsx", "tsx", "html", "css", "sql", "json", "xml", "yaml", "yml", "c", "cpp", "h", "go", "rs", "sh", "ps1").contains(ext)) {
            return "Code";
        }
        return "Other";
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.smartfms.web;

import com.smartfms.db.Database;
import com.smartfms.os.OsStorageService;
import com.smartfms.security.AuthService;
import com.smartfms.util.Json;
import com.smartfms.util.MultipartParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * REST API handler for authentication, files, folders, storage monitoring, and admin operations.
 */
public final class ApiHandler implements HttpHandler {
    private final Database db;
    private final OsStorageService os;

    public ApiHandler(Database db, OsStorageService os) {
        this.db = db;
        this.os = os;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);

        if ("OPTIONS".equals(method)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
            ex.sendResponseHeaders(204, -1);
            return;
        }

        try (Connection conn = db.getConnection()) {
            // Public auth routes
            if ("/api/auth/register".equals(path) && "POST".equals(method)) {
                handleRegister(ex, conn);
                return;
            }
            if ("/api/auth/login".equals(path) && "POST".equals(method)) {
                handleLogin(ex, conn);
                return;
            }

            // Authenticated routes
            String token = extractToken(ex);
            AuthService.UserSession user = AuthService.authenticate(conn, token);
            if (user == null) {
                sendJson(ex, 401, Map.of("error", "Unauthorized. Please sign in."));
                return;
            }

            switch (path) {
                case "/api/auth/logout" -> {
                    if ("POST".equals(method)) handleLogout(ex, conn, token, user);
                    else sendJson(ex, 405, Map.of("error", "Method not allowed"));
                }
                case "/api/auth/me" -> sendJson(ex, 200, userMap(user));
                case "/api/files" -> {
                    if ("GET".equals(method)) handleListFiles(ex, conn, user);
                    else if ("DELETE".equals(method)) handleDeleteFile(ex, conn, user);
                    else sendJson(ex, 405, Map.of("error", "Method not allowed"));
                }
                case "/api/files/upload" -> {
                    if ("POST".equals(method)) handleUpload(ex, conn, user);
                    else sendJson(ex, 405, Map.of("error", "Method not allowed"));
                }
                case "/api/files/download" -> {
                    if ("GET".equals(method)) handleDownload(ex, conn, user);
                    else sendJson(ex, 405, Map.of("error", "Method not allowed"));
                }
                case "/api/files/move" -> {
                    if ("POST".equals(method)) handleMoveFile(ex, conn, user);
                    else sendJson(ex, 405, Map.of("error", "Method not allowed"));
                }
                case "/api/files/update" -> {
                    if ("POST".equals(method)) handleUpdateFile(ex, conn, user);
                    else sendJson(ex, 405, Map.of("error", "Method not allowed"));
                }
                case "/api/folders" -> {
                    if ("GET".equals(method)) handleListFolders(ex, conn, user);
                    else if ("POST".equals(method)) handleCreateFolder(ex, conn, user);
                    else if ("DELETE".equals(method)) handleDeleteFolder(ex, conn, user);
                    else sendJson(ex, 405, Map.of("error", "Method not allowed"));
                }
                case "/api/storage/stats" -> handleStorageStats(ex, conn, user);
                case "/api/admin/overview" -> {
                    if (!"ADMIN".equals(user.role())) {
                        sendJson(ex, 403, Map.of("error", "Admin role required"));
                        return;
                    }
                    handleAdminOverview(ex, conn);
                }
                case "/api/admin/users/update" -> {
                    if (!"ADMIN".equals(user.role())) {
                        sendJson(ex, 403, Map.of("error", "Admin role required"));
                        return;
                    }
                    handleAdminUpdateUser(ex, conn, user);
                }
                case "/api/admin/files" -> {
                    if (!"ADMIN".equals(user.role())) {
                        sendJson(ex, 403, Map.of("error", "Admin role required"));
                        return;
                    }
                    handleAdminListFiles(ex, conn);
                }
                default -> sendJson(ex, 404, Map.of("error", "Endpoint not found"));
            }
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            sendJson(ex, 403, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Server error: " + e.getMessage()));
        }
    }

    private void handleRegister(HttpExchange ex, Connection conn) throws Exception {
        Map<String, Object> body = readJsonBody(ex);
        String username = trimOrNull(Json.str(body, "username"));
        String email = trimOrNull(Json.str(body, "email"));
        String password = Json.str(body, "password");

        if (username == null || username.length() < 3 || username.length() > 32) {
            sendJson(ex, 400, Map.of("error", "Username must be 3-32 characters"));
            return;
        }
        if (email == null || !email.contains("@")) {
            sendJson(ex, 400, Map.of("error", "Valid email is required"));
            return;
        }
        if (password == null || password.length() < 6) {
            sendJson(ex, 400, Map.of("error", "Password must be at least 6 characters"));
            return;
        }

        try (PreparedStatement check = conn.prepareStatement("SELECT id FROM users WHERE LOWER(username)=LOWER(?) OR LOWER(email)=LOWER(?)")) {
            check.setString(1, username);
            check.setString(2, email);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    sendJson(ex, 409, Map.of("error", "Username or email already exists"));
                    return;
                }
            }
        }

        long newUserId;
        String sql = "INSERT INTO users (username, email, password_hash, role) VALUES (?, ?, ?, 'USER')";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, AuthService.hashPassword(password));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Failed to create user");
                newUserId = keys.getLong(1);
            }
        }

        os.ensureUserRoot(newUserId);
        String token = AuthService.createSession(conn, newUserId);
        Database.logAudit(conn, newUserId, username, "REGISTER", "USER", "Registered new user account");

        AuthService.UserSession session = AuthService.authenticate(conn, token);
        sendJson(ex, 201, Map.of("token", token, "user", userMap(session)));
    }

    private void handleLogin(HttpExchange ex, Connection conn) throws Exception {
        Map<String, Object> body = readJsonBody(ex);
        String usernameOrEmail = trimOrNull(Json.str(body, "username"));
        String password = Json.str(body, "password");

        if (usernameOrEmail == null || password == null) {
            sendJson(ex, 400, Map.of("error", "Username and password are required"));
            return;
        }

        String sql = "SELECT id, username, email, password_hash, role, storage_quota_bytes, is_active FROM users WHERE LOWER(username)=LOWER(?) OR LOWER(email)=LOWER(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usernameOrEmail);
            ps.setString(2, usernameOrEmail);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    sendJson(ex, 401, Map.of("error", "Invalid credentials"));
                    return;
                }
                if (!rs.getBoolean("is_active")) {
                    sendJson(ex, 403, Map.of("error", "Account is disabled by administrator"));
                    return;
                }
                if (!AuthService.verifyPassword(password, rs.getString("password_hash"))) {
                    sendJson(ex, 401, Map.of("error", "Invalid credentials"));
                    return;
                }
                long userId = rs.getLong("id");
                String username = rs.getString("username");
                String token = AuthService.createSession(conn, userId);
                Database.logAudit(conn, userId, username, "LOGIN", "USER", "User signed in");
                AuthService.UserSession session = AuthService.authenticate(conn, token);
                sendJson(ex, 200, Map.of("token", token, "user", userMap(session)));
            }
        }
    }

    private void handleLogout(HttpExchange ex, Connection conn, String token, AuthService.UserSession user) throws Exception {
        AuthService.revokeSession(conn, token);
        Database.logAudit(conn, user.userId(), user.username(), "LOGOUT", "USER", "User signed out");
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void handleListFiles(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        Map<String, String> q = queryParams(ex);
        String search = q.getOrDefault("q", "").trim();
        String category = q.getOrDefault("category", "").trim();
        String folderIdStr = q.getOrDefault("folderId", "").trim();
        String sort = q.getOrDefault("sort", "newest").trim();

        StringBuilder sql = new StringBuilder("""
            SELECT f.id, f.folder_id, fld.name AS folder_name, f.original_name, f.mime_type,
                   f.category, f.size_bytes, f.sha256_hash, f.tags, f.os_rel_path,
                   f.created_at, f.updated_at
            FROM files f
            LEFT JOIN folders fld ON fld.id = f.folder_id
            WHERE f.user_id = ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(user.userId());

        if (!search.isEmpty()) {
            sql.append(" AND (LOWER(f.original_name) LIKE LOWER(?) OR LOWER(f.tags) LIKE LOWER(?))");
            params.add("%" + search + "%");
            params.add("%" + search + "%");
        }
        if (!category.isEmpty() && !"All".equalsIgnoreCase(category)) {
            sql.append(" AND f.category = ?");
            params.add(category);
        }
        if (!folderIdStr.isEmpty()) {
            if ("root".equalsIgnoreCase(folderIdStr)) {
                sql.append(" AND f.folder_id IS NULL");
            } else {
                sql.append(" AND f.folder_id = ?");
                params.add(Long.parseLong(folderIdStr));
            }
        }

        sql.append(switch (sort) {
            case "oldest" -> " ORDER BY f.created_at ASC";
            case "name" -> " ORDER BY LOWER(f.original_name) ASC";
            case "size_desc" -> " ORDER BY f.size_bytes DESC";
            case "size_asc" -> " ORDER BY f.size_bytes ASC";
            default -> " ORDER BY f.created_at DESC";
        });

        List<Map<String, Object>> files = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    files.add(fileRow(rs));
                }
            }
        }
        sendJson(ex, 200, Map.of("files", files));
    }

    private void handleUpload(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        byte[] body = readAllBytes(ex.getRequestBody());
        MultipartParser.ParsedMultipart parsed = MultipartParser.parse(body, contentType);

        if (parsed.files().isEmpty()) {
            sendJson(ex, 400, Map.of("error", "No file provided"));
            return;
        }

        String folderIdStr = parsed.fields().get("folderId");
        Long folderId = (folderIdStr != null && !folderIdStr.isBlank() && !"root".equalsIgnoreCase(folderIdStr))
                ? Long.parseLong(folderIdStr)
                : null;
        String tags = parsed.fields().getOrDefault("tags", "").trim();

        String folderRelPath = "";
        if (folderId != null) {
            folderRelPath = getFolderRelPath(conn, user.userId(), folderId);
            if (folderRelPath == null) {
                sendJson(ex, 404, Map.of("error", "Target folder not found"));
                return;
            }
        }

        long currentUsed = getUserUsedBytes(conn, user.userId());
        long incomingTotal = parsed.files().stream().mapToLong(f -> f.data().length).sum();
        if (currentUsed + incomingTotal > user.storageQuotaBytes()) {
            sendJson(ex, 413, Map.of("error", "Storage quota exceeded. Free up space or request an admin quota increase."));
            return;
        }

        List<Map<String, Object>> uploaded = new ArrayList<>();
        for (MultipartParser.FilePart part : parsed.files()) {
            OsStorageService.SavedFileInfo saved = os.saveFile(
                    user.userId(), folderRelPath, part.filename(), part.contentType(), part.data());

            String sql = """
                INSERT INTO files (user_id, folder_id, original_name, stored_name, os_rel_path,
                                   mime_type, category, size_bytes, sha256_hash, tags)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            long fileId;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, user.userId());
                if (folderId == null) ps.setNull(2, Types.BIGINT);
                else ps.setLong(2, folderId);
                ps.setString(3, part.filename());
                ps.setString(4, saved.storedName());
                ps.setString(5, saved.osRelPath());
                ps.setString(6, saved.mimeType());
                ps.setString(7, saved.category());
                ps.setLong(8, saved.sizeBytes());
                ps.setString(9, saved.sha256());
                ps.setString(10, tags);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    fileId = keys.getLong(1);
                }
            }

            Database.logAudit(conn, user.userId(), user.username(), "UPLOAD", "FILE",
                    "Uploaded " + part.filename() + " (" + saved.sizeBytes() + " bytes, " + saved.category() + ")");

            uploaded.add(Map.of(
                    "id", fileId,
                    "originalName", part.filename(),
                    "category", saved.category(),
                    "sizeBytes", saved.sizeBytes(),
                    "osRelPath", saved.osRelPath()
            ));
        }

        sendJson(ex, 201, Map.of("uploaded", uploaded));
    }

    private void handleDownload(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        Map<String, String> q = queryParams(ex);
        long fileId = Long.parseLong(q.getOrDefault("id", "0"));
        boolean inline = "1".equals(q.get("inline")) || "true".equalsIgnoreCase(q.get("inline"));

        String sql = "ADMIN".equals(user.role())
                ? "SELECT user_id, original_name, os_rel_path, mime_type FROM files WHERE id = ?"
                : "SELECT user_id, original_name, os_rel_path, mime_type FROM files WHERE id = ? AND user_id = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fileId);
            if (!"ADMIN".equals(user.role())) ps.setLong(2, user.userId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    sendJson(ex, 404, Map.of("error", "File not found"));
                    return;
                }
                long ownerId = rs.getLong("user_id");
                String origName = rs.getString("original_name");
                String osRel = rs.getString("os_rel_path");
                String mime = rs.getString("mime_type");

                byte[] data = os.readFile(ownerId, osRel);
                ex.getResponseHeaders().set("Content-Type", mime != null ? mime : "application/octet-stream");
                String disp = (inline ? "inline" : "attachment") + "; filename=\"" + origName.replace("\"", "") + "\"";
                ex.getResponseHeaders().set("Content-Disposition", disp);
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(data);
                }
            }
        }
    }

    private void handleMoveFile(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        Map<String, Object> body = readJsonBody(ex);
        Long fileId = Json.lng(body, "fileId");
        Long targetFolderId = Json.lng(body, "targetFolderId"); // null means root

        if (fileId == null) {
            sendJson(ex, 400, Map.of("error", "fileId is required"));
            return;
        }

        String targetRelPath = "";
        if (targetFolderId != null) {
            targetRelPath = getFolderRelPath(conn, user.userId(), targetFolderId);
            if (targetRelPath == null) {
                sendJson(ex, 404, Map.of("error", "Target folder not found"));
                return;
            }
        }

        String selectSql = "SELECT original_name, stored_name, os_rel_path FROM files WHERE id = ? AND user_id = ?";
        String origName, storedName, currentRelPath;
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setLong(1, fileId);
            ps.setLong(2, user.userId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    sendJson(ex, 404, Map.of("error", "File not found"));
                    return;
                }
                origName = rs.getString("original_name");
                storedName = rs.getString("stored_name");
                currentRelPath = rs.getString("os_rel_path");
            }
        }

        String newRelPath = os.moveFile(user.userId(), currentRelPath, targetRelPath, storedName);

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE files SET folder_id = ?, os_rel_path = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")) {
            if (targetFolderId == null) ps.setNull(1, Types.BIGINT);
            else ps.setLong(1, targetFolderId);
            ps.setString(2, newRelPath);
            ps.setLong(3, fileId);
            ps.setLong(4, user.userId());
            ps.executeUpdate();
        }

        Database.logAudit(conn, user.userId(), user.username(), "MOVE", "FILE",
                "Moved " + origName + " -> " + (targetRelPath.isEmpty() ? "/" : "/" + targetRelPath));
        sendJson(ex, 200, Map.of("ok", true, "osRelPath", newRelPath));
    }

    private void handleUpdateFile(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        Map<String, Object> body = readJsonBody(ex);
        Long fileId = Json.lng(body, "fileId");
        String newName = trimOrNull(Json.str(body, "originalName"));
        String category = trimOrNull(Json.str(body, "category"));
        String tags = Json.str(body, "tags");

        if (fileId == null) {
            sendJson(ex, 400, Map.of("error", "fileId is required"));
            return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE files SET original_name = COALESCE(?, original_name), category = COALESCE(?, category), tags = COALESCE(?, tags), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ?")) {
            ps.setString(1, newName);
            ps.setString(2, category);
            ps.setString(3, tags);
            ps.setLong(4, fileId);
            ps.setLong(5, user.userId());
            int rows = ps.executeUpdate();
            if (rows == 0) {
                sendJson(ex, 404, Map.of("error", "File not found"));
                return;
            }
        }

        Database.logAudit(conn, user.userId(), user.username(), "UPDATE", "FILE", "Updated file #" + fileId);
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void handleDeleteFile(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        Map<String, String> q = queryParams(ex);
        long fileId = Long.parseLong(q.getOrDefault("id", "0"));

        String sql = "ADMIN".equals(user.role())
                ? "SELECT user_id, original_name, os_rel_path FROM files WHERE id = ?"
                : "SELECT user_id, original_name, os_rel_path FROM files WHERE id = ? AND user_id = ?";

        long ownerId;
        String origName, osRelPath;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fileId);
            if (!"ADMIN".equals(user.role())) ps.setLong(2, user.userId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    sendJson(ex, 404, Map.of("error", "File not found"));
                    return;
                }
                ownerId = rs.getLong("user_id");
                origName = rs.getString("original_name");
                osRelPath = rs.getString("os_rel_path");
            }
        }

        os.deleteFile(ownerId, osRelPath);
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM files WHERE id = ?")) {
            ps.setLong(1, fileId);
            ps.executeUpdate();
        }

        Database.logAudit(conn, user.userId(), user.username(), "DELETE", "FILE", "Deleted " + origName);
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void handleListFolders(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        String sql = """
            SELECT fld.id, fld.parent_id, fld.name, fld.os_rel_path, fld.created_at,
                   COUNT(f.id) AS file_count, COALESCE(SUM(f.size_bytes), 0) AS total_bytes
            FROM folders fld
            LEFT JOIN files f ON f.folder_id = fld.id
            WHERE fld.user_id = ?
            GROUP BY fld.id, fld.parent_id, fld.name, fld.os_rel_path, fld.created_at
            ORDER BY LOWER(fld.name) ASC
            """;
        List<Map<String, Object>> folders = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, user.userId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    long p = rs.getLong("parent_id");
                    row.put("parentId", rs.wasNull() ? null : p);
                    row.put("name", rs.getString("name"));
                    row.put("osRelPath", rs.getString("os_rel_path"));
                    row.put("fileCount", rs.getInt("file_count"));
                    row.put("totalBytes", rs.getLong("total_bytes"));
                    row.put("createdAt", rs.getTimestamp("created_at").toString());
                    folders.add(row);
                }
            }
        }
        sendJson(ex, 200, Map.of("folders", folders));
    }

    private void handleCreateFolder(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        Map<String, Object> body = readJsonBody(ex);
        String name = trimOrNull(Json.str(body, "name"));
        Long parentId = Json.lng(body, "parentId");

        if (name == null || name.length() > 80) {
            sendJson(ex, 400, Map.of("error", "Folder name must be 1-80 characters"));
            return;
        }

        String safeFolder = OsStorageService.sanitizeFolderName(name);
        String relPath = safeFolder;
        if (parentId != null) {
            String parentRel = getFolderRelPath(conn, user.userId(), parentId);
            if (parentRel == null) {
                sendJson(ex, 404, Map.of("error", "Parent folder not found"));
                return;
            }
            relPath = parentRel + "/" + safeFolder;
        }

        // Prevent duplicate sibling path
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT id FROM folders WHERE user_id = ? AND os_rel_path = ?")) {
            check.setLong(1, user.userId());
            check.setString(2, relPath);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    sendJson(ex, 409, Map.of("error", "Folder already exists at this location"));
                    return;
                }
            }
        }

        os.createOsFolder(user.userId(), relPath);

        long folderId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO folders (user_id, parent_id, name, os_rel_path) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, user.userId());
            if (parentId == null) ps.setNull(2, Types.BIGINT);
            else ps.setLong(2, parentId);
            ps.setString(3, name);
            ps.setString(4, relPath);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                folderId = keys.getLong(1);
            }
        }

        Database.logAudit(conn, user.userId(), user.username(), "CREATE_FOLDER", "FOLDER", "Created folder /" + relPath);
        sendJson(ex, 201, Map.of("id", folderId, "name", name, "osRelPath", relPath));
    }

    private void handleDeleteFolder(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        Map<String, String> q = queryParams(ex);
        long folderId = Long.parseLong(q.getOrDefault("id", "0"));

        String relPath = getFolderRelPath(conn, user.userId(), folderId);
        if (relPath == null) {
            sendJson(ex, 404, Map.of("error", "Folder not found"));
            return;
        }

        os.deleteOsDirectoryRecursively(user.userId(), relPath);
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM folders WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, folderId);
            ps.setLong(2, user.userId());
            ps.executeUpdate();
        }

        Database.logAudit(conn, user.userId(), user.username(), "DELETE_FOLDER", "FOLDER", "Deleted folder /" + relPath);
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void handleStorageStats(HttpExchange ex, Connection conn, AuthService.UserSession user) throws Exception {
        long usedBytes = getUserUsedBytes(conn, user.userId());
        int totalFiles = 0;
        List<Map<String, Object>> byCategory = new ArrayList<>();

        String sql = "SELECT category, COUNT(*) AS cnt, COALESCE(SUM(size_bytes), 0) AS bytes FROM files WHERE user_id = ? GROUP BY category ORDER BY bytes DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, user.userId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int cnt = rs.getInt("cnt");
                    totalFiles += cnt;
                    byCategory.add(Map.of(
                            "category", rs.getString("category"),
                            "count", cnt,
                            "bytes", rs.getLong("bytes")
                    ));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usedBytes", usedBytes);
        result.put("quotaBytes", user.storageQuotaBytes());
        result.put("totalFiles", totalFiles);
        result.put("byCategory", byCategory);
        result.put("osTelemetry", os.getOsTelemetry());
        sendJson(ex, 200, result);
    }

    private void handleAdminOverview(HttpExchange ex, Connection conn) throws Exception {
        List<Map<String, Object>> users = new ArrayList<>();
        String userSql = """
            SELECT u.id, u.username, u.email, u.role, u.storage_quota_bytes, u.is_active, u.created_at,
                   COUNT(f.id) AS file_count, COALESCE(SUM(f.size_bytes), 0) AS used_bytes
            FROM users u
            LEFT JOIN files f ON f.user_id = u.id
            GROUP BY u.id, u.username, u.email, u.role, u.storage_quota_bytes, u.is_active, u.created_at
            ORDER BY u.id ASC
            """;
        long globalUsed = 0;
        int globalFiles = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(userSql)) {
            while (rs.next()) {
                long used = rs.getLong("used_bytes");
                int cnt = rs.getInt("file_count");
                globalUsed += used;
                globalFiles += cnt;
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("id", rs.getLong("id"));
                u.put("username", rs.getString("username"));
                u.put("email", rs.getString("email"));
                u.put("role", rs.getString("role"));
                u.put("storageQuotaBytes", rs.getLong("storage_quota_bytes"));
                u.put("isActive", rs.getBoolean("is_active"));
                u.put("fileCount", cnt);
                u.put("usedBytes", used);
                u.put("createdAt", rs.getTimestamp("created_at").toString());
                users.add(u);
            }
        }

        List<Map<String, Object>> logs = new ArrayList<>();
        String logSql = "SELECT id, username, action, target_type, details, created_at FROM audit_logs ORDER BY created_at DESC LIMIT 40";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(logSql)) {
            while (rs.next()) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("id", rs.getLong("id"));
                l.put("username", rs.getString("username"));
                l.put("action", rs.getString("action"));
                l.put("targetType", rs.getString("target_type"));
                l.put("details", rs.getString("details"));
                l.put("createdAt", rs.getTimestamp("created_at").toString());
                logs.add(l);
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("users", users);
        res.put("auditLogs", logs);
        res.put("globalUsedBytes", globalUsed);
        res.put("globalFileCount", globalFiles);
        res.put("osTelemetry", os.getOsTelemetry());
        sendJson(ex, 200, res);
    }

    private void handleAdminUpdateUser(HttpExchange ex, Connection conn, AuthService.UserSession admin) throws Exception {
        Map<String, Object> body = readJsonBody(ex);
        Long targetUserId = Json.lng(body, "userId");
        String role = Json.str(body, "role");
        Long quotaBytes = Json.lng(body, "storageQuotaBytes");
        Boolean isActive = Json.bool(body, "isActive");

        if (targetUserId == null) {
            sendJson(ex, 400, Map.of("error", "userId is required"));
            return;
        }
        if (targetUserId == admin.userId() && Boolean.FALSE.equals(isActive)) {
            sendJson(ex, 400, Map.of("error", "Cannot disable your own admin account"));
            return;
        }

        String sql = """
            UPDATE users
            SET role = COALESCE(?, role),
                storage_quota_bytes = COALESCE(?, storage_quota_bytes),
                is_active = COALESCE(?, is_active)
            WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            if (quotaBytes == null) ps.setNull(2, Types.BIGINT);
            else ps.setLong(2, quotaBytes);
            if (isActive == null) ps.setNull(3, Types.BOOLEAN);
            else ps.setBoolean(3, isActive);
            ps.setLong(4, targetUserId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                sendJson(ex, 404, Map.of("error", "User not found"));
                return;
            }
        }

        Database.logAudit(conn, admin.userId(), admin.username(), "ADMIN_UPDATE_USER", "USER",
                "Updated user #" + targetUserId + " (role=" + role + ", quota=" + quotaBytes + ", active=" + isActive + ")");
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void handleAdminListFiles(HttpExchange ex, Connection conn) throws Exception {
        String sql = """
            SELECT f.id, u.username AS owner_username, f.folder_id, fld.name AS folder_name,
                   f.original_name, f.mime_type, f.category, f.size_bytes, f.sha256_hash,
                   f.tags, f.os_rel_path, f.created_at, f.updated_at
            FROM files f
            JOIN users u ON u.id = f.user_id
            LEFT JOIN folders fld ON fld.id = f.folder_id
            ORDER BY f.created_at DESC
            LIMIT 200
            """;
        List<Map<String, Object>> files = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = fileRow(rs);
                row.put("ownerUsername", rs.getString("owner_username"));
                files.add(row);
            }
        }
        sendJson(ex, 200, Map.of("files", files));
    }

    private Map<String, Object> fileRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        long fid = rs.getLong("folder_id");
        m.put("folderId", rs.wasNull() ? null : fid);
        m.put("folderName", rs.getString("folder_name"));
        m.put("originalName", rs.getString("original_name"));
        m.put("mimeType", rs.getString("mime_type"));
        m.put("category", rs.getString("category"));
        m.put("sizeBytes", rs.getLong("size_bytes"));
        m.put("sha256Hash", rs.getString("sha256_hash"));
        m.put("tags", rs.getString("tags"));
        m.put("osRelPath", rs.getString("os_rel_path"));
        m.put("createdAt", rs.getTimestamp("created_at").toString());
        m.put("updatedAt", rs.getTimestamp("updated_at").toString());
        return m;
    }

    private String getFolderRelPath(Connection conn, long userId, long folderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT os_rel_path FROM folders WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, folderId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("os_rel_path") : null;
            }
        }
    }

    private long getUserUsedBytes(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(size_bytes), 0) FROM files WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static Map<String, Object> userMap(AuthService.UserSession u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.userId());
        m.put("username", u.username());
        m.put("email", u.email());
        m.put("role", u.role());
        m.put("storageQuotaBytes", u.storageQuotaBytes());
        return m;
    }

    private static String extractToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        Map<String, String> q = queryParams(ex);
        return q.get("token");
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        byte[] bytes = readAllBytes(ex.getRequestBody());
        return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        return is.readAllBytes();
    }

    private static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}

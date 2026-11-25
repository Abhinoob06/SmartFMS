package com.smartfms.db;

import com.smartfms.os.OsStorageService;
import com.smartfms.security.AuthService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

/**
 * RDBMS connection manager and schema initializer (H2 embedded SQL database).
 */
public final class Database {
    private final String jdbcUrl;

    public Database(Path dataDir) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Path dbPath = dataDir.resolve("smartfms").toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:h2:file:" + dbPath.toString().replace('\\', '/') + ";AUTO_SERVER=FALSE";
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 JDBC driver not found on classpath", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    public void initSchemaAndSeed(Path schemaPath, OsStorageService osStorage) throws IOException, SQLException {
        String sql = Files.readString(schemaPath, StandardCharsets.UTF_8);
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            for (String stmt : sql.split(";")) {
                StringBuilder clean = new StringBuilder();
                for (String line : stmt.split("\\r?\\n")) {
                    if (!line.trim().startsWith("--")) {
                        clean.append(line).append("\n");
                    }
                }
                String exec = clean.toString().trim();
                if (!exec.isEmpty()) {
                    st.execute(exec);
                }
            }
            seedDefaultUsers(conn, osStorage);
        }
    }

    private void seedDefaultUsers(Connection conn, OsStorageService osStorage) throws SQLException, IOException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        long adminId = insertUser(conn, "admin", "admin@smartfms.local", "admin123", "ADMIN", 2L * 1024 * 1024 * 1024);
        long demoId = insertUser(conn, "demo", "demo@smartfms.local", "demo123", "USER", 500L * 1024 * 1024);

        osStorage.ensureUserRoot(adminId);
        osStorage.ensureUserRoot(demoId);

        // Create starter folders for demo user in RDBMS + OS
        createStarterFolder(conn, osStorage, demoId, "Work Documents");
        createStarterFolder(conn, osStorage, demoId, "Media");
        createStarterFolder(conn, osStorage, demoId, "Projects");

        logAudit(conn, adminId, "admin", "SYSTEM_INIT", "SYSTEM", "Initialized RDBMS schema and seeded default accounts");
    }

    private long insertUser(Connection conn, String username, String email, String rawPassword, String role, long quota) throws SQLException {
        String sql = "INSERT INTO users (username, email, password_hash, role, storage_quota_bytes) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, AuthService.hashPassword(rawPassword));
            ps.setString(4, role);
            ps.setLong(5, quota);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("Failed to insert user " + username);
    }

    private void createStarterFolder(Connection conn, OsStorageService osStorage, long userId, String name) throws SQLException, IOException {
        String rel = OsStorageService.sanitizeFolderName(name);
        osStorage.createOsFolder(userId, rel);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO folders (user_id, parent_id, name, os_rel_path) VALUES (?, NULL, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, rel);
            ps.executeUpdate();
        }
    }

    public static void logAudit(Connection conn, Long userId, String username, String action, String targetType, String details) {
        String sql = "INSERT INTO audit_logs (user_id, username, action, target_type, details) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId == null) ps.setNull(1, Types.BIGINT);
            else ps.setLong(1, userId);
            ps.setString(2, username);
            ps.setString(3, action);
            ps.setString(4, targetType);
            ps.setString(5, details.length() > 500 ? details.substring(0, 500) : details);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}

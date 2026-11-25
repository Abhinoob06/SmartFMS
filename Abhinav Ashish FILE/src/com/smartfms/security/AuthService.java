package com.smartfms.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * PBKDF2 password hashing and RDBMS-backed session authentication.
 */
public final class AuthService {
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RNG = new SecureRandom();

    public record UserSession(long userId, String username, String email, String role, long storageQuotaBytes, boolean isActive) {}

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verifyPassword(String password, String stored) {
        if (stored == null || !stored.contains(":")) return false;
        String[] parts = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        byte[] actual = pbkdf2(password.toCharArray(), salt);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }

    public static String createSession(Connection conn, long userId) throws SQLException {
        byte[] tokenBytes = new byte[32];
        RNG.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS));

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, token);
            ps.setLong(2, userId);
            ps.setTimestamp(3, expiresAt);
            ps.executeUpdate();
        }
        return token;
    }

    public static UserSession authenticate(Connection conn, String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
        String sql = """
            SELECT u.id, u.username, u.email, u.role, u.storage_quota_bytes, u.is_active
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token = ? AND s.expires_at > CURRENT_TIMESTAMP
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    boolean active = rs.getBoolean("is_active");
                    if (!active) return null;
                    return new UserSession(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("email"),
                            rs.getString("role"),
                            rs.getLong("storage_quota_bytes"),
                            active
                    );
                }
            }
        }
        return null;
    }

    public static void revokeSession(Connection conn, String token) throws SQLException {
        if (token == null || token.isBlank()) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        }
    }
}

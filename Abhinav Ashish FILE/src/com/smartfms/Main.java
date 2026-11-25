package com.smartfms;

import com.smartfms.db.Database;
import com.smartfms.os.OsStorageService;
import com.smartfms.web.ApiHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Main entry point for the Smart File Management System (Java + RDBMS + OS).
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path root = Path.of("").toAbsolutePath().normalize();

        OsStorageService osStorage = new OsStorageService(root.resolve("storage"));
        Database db = new Database(root.resolve("data"));
        db.initSchemaAndSeed(root.resolve("sql").resolve("schema.sql"), osStorage);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", new ApiHandler(db, osStorage));
        server.createContext("/", new StaticFileHandler(root.resolve("web")));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("====================================================");
        System.out.println("  Smart File Management System (Java + RDBMS + OS)");
        System.out.println("  URL: http://localhost:" + port);
        System.out.println("  OS Storage Root: " + osStorage.getStorageRoot());
        System.out.println("  Seeded Accounts: admin/admin123 (ADMIN), demo/demo123 (USER)");
        System.out.println("====================================================");
    }

    private static final class StaticFileHandler implements HttpHandler {
        private final Path webRoot;

        StaticFileHandler(Path webRoot) {
            this.webRoot = webRoot.toAbsolutePath().normalize();
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String reqPath = ex.getRequestURI().getPath();
            if ("/".equals(reqPath)) reqPath = "/index.html";
            String rel = reqPath.startsWith("/") ? reqPath.substring(1) : reqPath;
            Path target = webRoot.resolve(rel).normalize();

            if (!target.startsWith(webRoot) || !Files.exists(target) || Files.isDirectory(target)) {
                target = webRoot.resolve("index.html");
            }
            if (!Files.exists(target)) {
                byte[] msg = "404 Not Found".getBytes();
                ex.sendResponseHeaders(404, msg.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(msg);
                }
                return;
            }

            byte[] content = Files.readAllBytes(target);
            ex.getResponseHeaders().set("Content-Type", contentType(target.getFileName().toString()));
            ex.sendResponseHeaders(200, content.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(content);
            }
        }

        private static String contentType(String name) {
            if (name.endsWith(".html")) return "text/html; charset=utf-8";
            if (name.endsWith(".css")) return "text/css; charset=utf-8";
            if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (name.endsWith(".svg")) return "image/svg+xml";
            if (name.endsWith(".png")) return "image/png";
            return "application/octet-stream";
        }
    }
}

# SmartFMS — Smart File Management System

A full-stack web application for uploading, organizing, searching, moving, and deleting files, built with **Java**, an **RDBMS (H2 SQL Database)**, and **OS-level filesystem & system telemetry**.

---

## Tech Stack

- **Backend**: Java (JDK 25), built-in `com.sun.net.httpserver.HttpServer` with virtual threads, PBKDF2WithHmacSHA256 password hashing, and token-based sessions.
- **RDBMS**: H2 Database (`lib/h2-2.3.232.jar`) in persistent file-backed mode (`./data/smartfms`), with normalized tables, foreign keys, indexes, and SQL aggregate queries.
- **OS Integration**: `java.nio.file` for per-user directory trees (`./storage/users/<userId>/`), SHA-256 checksums, `Files.probeContentType`, `Files.move`, `Files.deleteIfExists`, `FileStore` disk capacity monitoring, and `OperatingSystemMXBean` system telemetry.
- **Frontend**: Vanilla HTML5, CSS3, and JavaScript (`web/index.html`, `web/styles.css`, `web/app.js`).

---

## Features

- **User Authentication & Roles**:
  - User registration and login with salted PBKDF2 password hashing.
  - `USER` and `ADMIN` roles with role-based access control.
- **File Operations**:
  - Drag-and-drop and multi-file upload (`multipart/form-data`).
  - Automatic file categorization (`Documents`, `Images`, `Videos`, `Audio`, `Archives`, `Code`, `Other`) with manual override and tags.
  - Organize files into folders (create/delete folders synced between RDBMS and OS).
  - Move files between folders (updates both RDBMS metadata and physical OS file path).
  - View/preview, download, rename, and delete files.
- **Search & Filtering**:
  - Search by filename or tags, filter by category and folder, and sort by date, name, or size.
- **Storage Usage & OS Monitoring**:
  - Per-user storage quota bar and category breakdown.
  - Live host OS & disk telemetry (OS name/version/arch, CPU cores, volume total/usable/used bytes, JVM memory, storage root path).
- **Admin Console** (`ADMIN` role only):
  - Manage user roles, storage quotas (in MB), and active/disabled account status.
  - Inspect and manage files across all users.
  - View RDBMS audit logs (`REGISTER`, `LOGIN`, `UPLOAD`, `MOVE`, `DELETE`, `CREATE_FOLDER`, `ADMIN_UPDATE_USER`, etc.).

---

## Project Structure

```text
.
├── lib/
│   └── h2-2.3.232.jar                  # H2 RDBMS JDBC driver
├── sql/
│   └── schema.sql                      # RDBMS schema (users, sessions, folders, files, audit_logs)
├── src/com/smartfms/
│   ├── Main.java                       # HTTP server entry point & static file handler
│   ├── db/Database.java                # JDBC connection manager, schema init & seeding
│   ├── os/OsStorageService.java        # OS filesystem ops, categorization, FileStore & OS metrics
│   ├── security/AuthService.java       # PBKDF2 hashing & RDBMS session auth
│   ├── util/Json.java                  # Lightweight JSON parser & serializer
│   ├── util/MultipartParser.java       # Binary-safe multipart/form-data parser
│   └── web/ApiHandler.java             # REST API endpoints
├── web/
│   ├── index.html                      # Single-page app UI
│   ├── styles.css                      # Dark-theme responsive styles
│   └── app.js                          # Frontend state & API integration
├── data/                               # H2 RDBMS files (auto-created on first run)
├── storage/                            # OS file storage root (auto-created on first run)
└── run.ps1                             # PowerShell compile & run script
```

---

## How to Run

### Prerequisites
- Java JDK 25 installed at `C:\Program Files\Java\jdk-25` (or update the path in `run.ps1`).

### Using `run.ps1` (PowerShell)
```powershell
# Optional: specify port (defaults to 8080)
$env:PORT = "8085"
.\run.ps1
```

### Manual Compile & Run
```powershell
javac -d out -cp "lib/h2-2.3.232.jar" (Get-ChildItem -Path "src" -Recurse -Filter "*.java").FullName
$env:PORT = "8085"
java -cp "out;lib/h2-2.3.232.jar" com.smartfms.Main
```

Open **`http://localhost:8085`** (or `http://localhost:8080` if using the default port) in your browser.

---

## Default Seeded Accounts

| Username | Password   | Role    | Default Quota |
|----------|------------|---------|---------------|
| `admin`  | `admin123` | `ADMIN` | 2 GB          |
| `demo`   | `demo123`  | `USER`  | 500 MB        |

*(New users can also register directly from the Sign In / Register screen.)*

---

## REST API Overview

- `POST /api/auth/register` — Register a new `USER` account
- `POST /api/auth/login` — Sign in and receive a bearer token
- `POST /api/auth/logout` — Revoke current session
- `GET  /api/auth/me` — Current authenticated user profile
- `GET  /api/files` — List/search/filter/sort files (`q`, `category`, `folderId`, `sort`)
- `POST /api/files/upload` — Upload one or more files (`multipart/form-data`)
- `GET  /api/files/download?id=...` — Download or inline-preview (`inline=1`) a file
- `POST /api/files/move` — Move a file to another folder (or root)
- `POST /api/files/update` — Rename, recategorize, or update tags
- `DELETE /api/files?id=...` — Delete a file from OS and RDBMS
- `GET  /api/folders` / `POST /api/folders` / `DELETE /api/folders?id=...` — Manage folders
- `GET  /api/storage/stats` — User quota, category breakdown, and OS telemetry
- `GET  /api/admin/overview` — Admin: all users, global stats, OS telemetry, audit logs
- `POST /api/admin/users/update` — Admin: update user role, quota, or active status
- `GET  /api/admin/files` — Admin: list files across all users

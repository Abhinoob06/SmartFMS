const API_BASE = (window.location.port === "8085" || window.location.port === "8080")
  ? ""
  : "http://localhost:8085";

const state = {
  token: localStorage.getItem("smartfms_token") || null,
  user: null,
  folders: [],
  files: [],
  selectedFolderId: "", // "" = All, "root" = Root only, or numeric id
  selectedCategory: "All",
  searchQuery: "",
  sort: "newest",
  activeView: "files"
};

const $ = (id) => document.getElementById(id);

function fmtBytes(bytes) {
  if (!bytes || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 2) + " " + units[i];
}

function esc(str) {
  return String(str ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

function showToast(msg) {
  const t = $("toast");
  t.textContent = msg;
  t.classList.remove("hidden");
  clearTimeout(showToast._timer);
  showToast._timer = setTimeout(() => t.classList.add("hidden"), 3000);
}

async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  if (opts.body && !(opts.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(opts.body);
  }
  const res = await fetch(API_BASE + path, { ...opts, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    if (res.status === 401 && state.token) {
      logoutLocal();
    }
    throw new Error(data.error || "Request failed (" + res.status + ")");
  }
  return data;
}

/* Auth */
function logoutLocal() {
  state.token = null;
  state.user = null;
  localStorage.removeItem("smartfms_token");
  $("app-shell").classList.add("hidden");
  $("auth-screen").classList.remove("hidden");
}

function showAuthError(msg) {
  const el = $("auth-error");
  if (!msg) { el.classList.add("hidden"); return; }
  el.textContent = msg;
  el.classList.remove("hidden");
}

async function initSession() {
  if (!state.token) return;
  try {
    state.user = await api("/api/auth/me");
    enterApp();
  } catch {
    logoutLocal();
  }
}

async function enterApp() {
  $("auth-screen").classList.add("hidden");
  $("app-shell").classList.remove("hidden");
  $("user-badge").textContent = `${state.user.username} (${state.user.role})`;
  $("nav-admin").classList.toggle("hidden", state.user.role !== "ADMIN");
  await Promise.all([loadFolders(), loadFiles(), loadSidebarStorage()]);
}

/* Folders & Files */
async function loadFolders() {
  const data = await api("/api/folders");
  state.folders = data.folders || [];
  renderFolders();
}

function renderFolders() {
  const list = $("folder-list");
  const items = [
    { id: "", name: "All Files", count: null },
    { id: "root", name: "Root (/)", count: null },
    ...state.folders.map((f) => ({ id: String(f.id), name: f.name, count: f.fileCount, deletable: true }))
  ];
  list.innerHTML = items.map((item) => `
    <div class="folder-item ${state.selectedFolderId === item.id ? "active" : ""}" data-fid="${item.id}">
      <span>${esc(item.name)}${item.count !== null ? ` <small class="muted">(${item.count})</small>` : ""}</span>
      ${item.deletable ? `<button class="folder-del" data-del-folder="${item.id}" title="Delete folder">&times;</button>` : ""}
    </div>
  `).join("");

  list.querySelectorAll(".folder-item").forEach((el) => {
    el.addEventListener("click", (e) => {
      if (e.target.dataset.delFolder) return;
      state.selectedFolderId = el.dataset.fid;
      const found = items.find((i) => i.id === state.selectedFolderId);
      $("current-folder-title").textContent = found ? found.name : "All Files";
      $("drop-target-name").textContent = (state.selectedFolderId && state.selectedFolderId !== "root")
        ? found.name : "Root";
      renderFolders();
      loadFiles();
    });
  });

  list.querySelectorAll("[data-del-folder]").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const id = btn.dataset.delFolder;
      if (!confirm("Delete this folder and all files inside it from OS & RDBMS?")) return;
      try {
        await api(`/api/folders?id=${id}`, { method: "DELETE" });
        if (state.selectedFolderId === id) state.selectedFolderId = "";
        showToast("Folder deleted");
        await Promise.all([loadFolders(), loadFiles(), loadSidebarStorage()]);
      } catch (err) {
        alert(err.message);
      }
    });
  });
}

async function loadFiles() {
  const params = new URLSearchParams();
  if (state.searchQuery) params.set("q", state.searchQuery);
  if (state.selectedCategory && state.selectedCategory !== "All") params.set("category", state.selectedCategory);
  if (state.selectedFolderId) params.set("folderId", state.selectedFolderId);
  params.set("sort", state.sort);

  const data = await api("/api/files?" + params.toString());
  state.files = data.files || [];
  renderFiles();
}

function renderFiles() {
  const tbody = $("files-tbody");
  const empty = $("empty-files");
  $("file-count-subtitle").textContent = `${state.files.length} file${state.files.length === 1 ? "" : "s"}`;

  if (!state.files.length) {
    tbody.innerHTML = "";
    empty.classList.remove("hidden");
    return;
  }
  empty.classList.add("hidden");

  tbody.innerHTML = state.files.map((f) => `
    <tr>
      <td>
        <strong>${esc(f.originalName)}</strong>
        ${f.tags ? `<div><small class="muted">Tags: ${esc(f.tags)}</small></div>` : ""}
      </td>
      <td><span class="badge">${esc(f.category)}</span></td>
      <td>${f.folderName ? esc(f.folderName) : '<span class="muted">Root</span>'}</td>
      <td>${fmtBytes(f.sizeBytes)}</td>
      <td><small class="muted">${esc(f.updatedAt.slice(0, 16))}</small></td>
      <td class="right">
        <button class="btn sm ghost" data-action="preview" data-id="${f.id}">View</button>
        <button class="btn sm ghost" data-action="download" data-id="${f.id}">Download</button>
        <button class="btn sm ghost" data-action="move" data-id="${f.id}">Move</button>
        <button class="btn sm ghost" data-action="edit" data-id="${f.id}">Edit</button>
        <button class="btn sm danger" data-action="delete" data-id="${f.id}">Delete</button>
      </td>
    </tr>
  `).join("");

  tbody.querySelectorAll("button[data-action]").forEach((btn) => {
    btn.addEventListener("click", () => handleFileAction(btn.dataset.action, Number(btn.dataset.id)));
  });
}

async function handleFileAction(action, fileId) {
  const file = state.files.find((x) => x.id === fileId);
  if (!file) return;

  if (action === "download") {
    window.open(`${API_BASE}/api/files/download?id=${fileId}&token=${encodeURIComponent(state.token)}`, "_blank");
    return;
  }
  if (action === "preview") {
    const url = `${API_BASE}/api/files/download?id=${fileId}&inline=1&token=${encodeURIComponent(state.token)}`;
    window.open(url, "_blank");
    return;
  }
  if (action === "delete") {
    if (!confirm(`Delete "${file.originalName}" from OS disk and RDBMS?`)) return;
    try {
      await api(`/api/files?id=${fileId}`, { method: "DELETE" });
      showToast("File deleted");
      await Promise.all([loadFiles(), loadFolders(), loadSidebarStorage()]);
    } catch (err) {
      alert(err.message);
    }
    return;
  }
  if (action === "move") {
    const options = `<option value="">Root (/)</option>` +
      state.folders.map((fld) => `<option value="${fld.id}" ${file.folderId === fld.id ? "selected" : ""}>${esc(fld.name)}</option>`).join("");
    openModal("Move File", `
      <p class="muted">Moving <strong>${esc(file.originalName)}</strong></p>
      <label>Target Folder
        <select id="move-target">${options}</select>
      </label>
      <div class="modal-actions">
        <button class="btn primary" id="confirm-move">Move File</button>
      </div>
    `);
    $("confirm-move").onclick = async () => {
      const val = $("move-target").value;
      try {
        await api("/api/files/move", {
          method: "POST",
          body: { fileId, targetFolderId: val ? Number(val) : null }
        });
        closeModal();
        showToast("File moved");
        await Promise.all([loadFiles(), loadFolders()]);
      } catch (err) {
        alert(err.message);
      }
    };
    return;
  }
  if (action === "edit") {
    const cats = ["Documents", "Images", "Videos", "Audio", "Archives", "Code", "Other"];
    openModal("Edit File Metadata", `
      <label>Filename
        <input type="text" id="edit-name" value="${esc(file.originalName)}" />
      </label>
      <label>Category
        <select id="edit-cat">
          ${cats.map((c) => `<option value="${c}" ${file.category === c ? "selected" : ""}>${c}</option>`).join("")}
        </select>
      </label>
      <label>Tags
        <input type="text" id="edit-tags" value="${esc(file.tags || "")}" placeholder="comma-separated" />
      </label>
      <div class="modal-actions">
        <button class="btn primary" id="confirm-edit">Save Changes</button>
      </div>
    `);
    $("confirm-edit").onclick = async () => {
      try {
        await api("/api/files/update", {
          method: "POST",
          body: {
            fileId,
            originalName: $("edit-name").value,
            category: $("edit-cat").value,
            tags: $("edit-tags").value
          }
        });
        closeModal();
        showToast("File updated");
        await loadFiles();
      } catch (err) {
        alert(err.message);
      }
    };
  }
}

async function uploadFiles(fileList) {
  if (!fileList || !fileList.length) return;
  const form = new FormData();
  if (state.selectedFolderId && state.selectedFolderId !== "root") {
    form.append("folderId", state.selectedFolderId);
  }
  form.append("tags", $("upload-tags").value.trim());
  for (const f of fileList) {
    form.append("file", f);
  }
  try {
    const res = await api("/api/files/upload", { method: "POST", body: form });
    showToast(`Uploaded ${res.uploaded.length} file(s)`);
    $("upload-tags").value = "";
    $("file-input").value = "";
    await Promise.all([loadFiles(), loadFolders(), loadSidebarStorage()]);
  } catch (err) {
    alert(err.message);
  }
}

/* Storage & OS Monitor */
async function loadSidebarStorage() {
  try {
    const data = await api("/api/storage/stats");
    const pct = data.quotaBytes > 0 ? Math.min(100, Math.round((data.usedBytes / data.quotaBytes) * 100)) : 0;
    $("sidebar-quota-pct").textContent = pct + "%";
    $("sidebar-quota-bar").style.width = pct + "%";
    $("sidebar-quota-text").textContent = `${fmtBytes(data.usedBytes)} / ${fmtBytes(data.quotaBytes)}`;
    return data;
  } catch {
    return null;
  }
}

async function loadStorageView() {
  const data = await loadSidebarStorage();
  if (!data) return;

  const os = data.osTelemetry || {};
  $("storage-cards").innerHTML = `
    <div class="stat-card"><div class="label">Your Used Storage</div><div class="value">${fmtBytes(data.usedBytes)}</div></div>
    <div class="stat-card"><div class="label">Your Quota</div><div class="value">${fmtBytes(data.quotaBytes)}</div></div>
    <div class="stat-card"><div class="label">Your Total Files</div><div class="value">${data.totalFiles}</div></div>
    <div class="stat-card"><div class="label">OS Disk Usable</div><div class="value">${fmtBytes(os.diskUsableBytes || 0)}</div></div>
  `;

  const cats = data.byCategory || [];
  $("category-breakdown").innerHTML = cats.length ? cats.map((c) => {
    const pct = data.usedBytes > 0 ? Math.round((c.bytes / data.usedBytes) * 100) : 0;
    return `
      <div class="breakdown-row">
        <div class="breakdown-top">
          <span><strong>${esc(c.category)}</strong> (${c.count} files)</span>
          <span>${fmtBytes(c.bytes)} (${pct}%)</span>
        </div>
        <div class="progress-track"><div class="progress-fill" style="width:${pct}%"></div></div>
      </div>
    `;
  }).join("") : '<p class="muted">No files uploaded yet.</p>';

  const rows = [
    ["Operating System", `${os.osName || ""} ${os.osVersion || ""} (${os.osArch || ""})`],
    ["Java Runtime", os.javaVersion || ""],
    ["CPU Cores", os.availableProcessors || ""],
    ["Disk Volume", `${os.diskName || ""} (${os.diskType || ""})`],
    ["Disk Total / Used", `${fmtBytes(os.diskTotalBytes)} / ${fmtBytes(os.diskUsedBytes)}`],
    ["JVM Memory (Free / Total)", `${fmtBytes(os.jvmFreeMemoryBytes)} / ${fmtBytes(os.jvmTotalMemoryBytes)}`],
    ["OS Storage Root", os.storageRootPath || ""]
  ];
  $("os-telemetry-list").innerHTML = rows.map(([k, v]) =>
    `<div class="kv-row"><span class="kv-key">${esc(k)}</span><span class="kv-val">${esc(v)}</span></div>`
  ).join("");
}

/* Admin Console */
async function loadAdminView() {
  const [overview, allFiles] = await Promise.all([
    api("/api/admin/overview"),
    api("/api/admin/files")
  ]);

  $("admin-cards").innerHTML = `
    <div class="stat-card"><div class="label">Total Users</div><div class="value">${overview.users.length}</div></div>
    <div class="stat-card"><div class="label">Global Files</div><div class="value">${overview.globalFileCount}</div></div>
    <div class="stat-card"><div class="label">Global Storage Used</div><div class="value">${fmtBytes(overview.globalUsedBytes)}</div></div>
    <div class="stat-card"><div class="label">OS Disk Free</div><div class="value">${fmtBytes(overview.osTelemetry?.diskUsableBytes || 0)}</div></div>
  `;

  $("admin-users-tbody").innerHTML = overview.users.map((u) => `
    <tr>
      <td><strong>${esc(u.username)}</strong><div><small class="muted">${esc(u.email)}</small></div></td>
      <td><span class="badge ${u.role === "ADMIN" ? "admin" : ""}">${esc(u.role)}</span></td>
      <td>${u.fileCount}</td>
      <td>${fmtBytes(u.usedBytes)} / ${fmtBytes(u.storageQuotaBytes)}</td>
      <td><span class="badge ${u.isActive ? "ok" : "off"}">${u.isActive ? "Active" : "Disabled"}</span></td>
      <td class="right">
        <button class="btn sm ghost" data-admin-user="${u.id}">Manage</button>
      </td>
    </tr>
  `).join("");

  $("admin-users-tbody").querySelectorAll("[data-admin-user]").forEach((btn) => {
    btn.onclick = () => {
      const u = overview.users.find((x) => x.id === Number(btn.dataset.adminUser));
      if (!u) return;
      const quotaMb = Math.round(u.storageQuotaBytes / (1024 * 1024));
      openModal("Manage User: " + u.username, `
        <label>Role
          <select id="adm-role">
            <option value="USER" ${u.role === "USER" ? "selected" : ""}>USER</option>
            <option value="ADMIN" ${u.role === "ADMIN" ? "selected" : ""}>ADMIN</option>
          </select>
        </label>
        <label>Storage Quota (MB)
          <input type="number" id="adm-quota-mb" value="${quotaMb}" min="10" />
        </label>
        <label>Account Status
          <select id="adm-active">
            <option value="true" ${u.isActive ? "selected" : ""}>Active</option>
            <option value="false" ${!u.isActive ? "selected" : ""}>Disabled</option>
          </select>
        </label>
        <div class="modal-actions">
          <button class="btn primary" id="adm-save-user">Save User</button>
        </div>
      `);
      $("adm-save-user").onclick = async () => {
        try {
          await api("/api/admin/users/update", {
            method: "POST",
            body: {
              userId: u.id,
              role: $("adm-role").value,
              storageQuotaBytes: Number($("adm-quota-mb").value) * 1024 * 1024,
              isActive: $("adm-active").value === "true"
            }
          });
          closeModal();
          showToast("User updated");
          loadAdminView();
        } catch (err) {
          alert(err.message);
        }
      };
    };
  });

  $("admin-files-tbody").innerHTML = (allFiles.files || []).map((f) => `
    <tr>
      <td><strong>${esc(f.ownerUsername)}</strong></td>
      <td>${esc(f.originalName)}</td>
      <td><span class="badge">${esc(f.category)}</span></td>
      <td>${fmtBytes(f.sizeBytes)}</td>
      <td><small class="muted">${esc(f.osRelPath)}</small></td>
      <td class="right">
        <button class="btn sm ghost" data-adm-dl="${f.id}">Download</button>
        <button class="btn sm danger" data-adm-del="${f.id}">Delete</button>
      </td>
    </tr>
  `).join("");

  $("admin-files-tbody").querySelectorAll("[data-adm-dl]").forEach((b) => {
    b.onclick = () => window.open(`${API_BASE}/api/files/download?id=${b.dataset.admDl}&token=${encodeURIComponent(state.token)}`, "_blank");
  });
  $("admin-files-tbody").querySelectorAll("[data-adm-del]").forEach((b) => {
    b.onclick = async () => {
      if (!confirm("Admin delete this file?")) return;
      await api(`/api/files?id=${b.dataset.admDel}`, { method: "DELETE" });
      showToast("File deleted by admin");
      loadAdminView();
    };
  });

  $("admin-logs-tbody").innerHTML = (overview.auditLogs || []).map((l) => `
    <tr>
      <td><small class="muted">${esc(l.createdAt.slice(0, 19))}</small></td>
      <td>${esc(l.username)}</td>
      <td><span class="badge">${esc(l.action)}</span></td>
      <td>${esc(l.targetType)}</td>
      <td>${esc(l.details)}</td>
    </tr>
  `).join("");
}

/* Modal */
function openModal(title, html) {
  $("modal-title").textContent = title;
  $("modal-body").innerHTML = html;
  $("modal-backdrop").classList.remove("hidden");
}
function closeModal() {
  $("modal-backdrop").classList.add("hidden");
}

/* Event Bindings */
$("tab-login").onclick = () => {
  $("tab-login").classList.add("active");
  $("tab-register").classList.remove("active");
  $("login-form").classList.remove("hidden");
  $("register-form").classList.add("hidden");
  showAuthError(null);
};
$("tab-register").onclick = () => {
  $("tab-register").classList.add("active");
  $("tab-login").classList.remove("active");
  $("register-form").classList.remove("hidden");
  $("login-form").classList.add("hidden");
  showAuthError(null);
};

$("fill-demo").onclick = () => {
  $("login-username").value = "demo";
  $("login-password").value = "demo123";
};
$("fill-admin").onclick = () => {
  $("login-username").value = "admin";
  $("login-password").value = "admin123";
};

$("login-form").onsubmit = async (e) => {
  e.preventDefault();
  showAuthError(null);
  try {
    const res = await api("/api/auth/login", {
      method: "POST",
      body: { username: $("login-username").value, password: $("login-password").value }
    });
    state.token = res.token;
    state.user = res.user;
    localStorage.setItem("smartfms_token", res.token);
    enterApp();
  } catch (err) {
    showAuthError(err.message);
  }
};

$("register-form").onsubmit = async (e) => {
  e.preventDefault();
  showAuthError(null);
  try {
    const res = await api("/api/auth/register", {
      method: "POST",
      body: {
        username: $("reg-username").value,
        email: $("reg-email").value,
        password: $("reg-password").value
      }
    });
    state.token = res.token;
    state.user = res.user;
    localStorage.setItem("smartfms_token", res.token);
    enterApp();
  } catch (err) {
    showAuthError(err.message);
  }
};

$("btn-logout").onclick = async () => {
  try { await api("/api/auth/logout", { method: "POST" }); } catch {}
  logoutLocal();
};

document.querySelectorAll(".nav-item").forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll(".nav-item").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const view = btn.dataset.view;
    state.activeView = view;
    $("view-files").classList.toggle("hidden", view !== "files");
    $("view-storage").classList.toggle("hidden", view !== "storage");
    $("view-admin").classList.toggle("hidden", view !== "admin");
    if (view === "storage") loadStorageView();
    if (view === "admin") loadAdminView();
  };
});

$("btn-new-folder").onclick = () => {
  openModal("Create New Folder", `
    <label>Folder Name
      <input type="text" id="new-folder-name" placeholder="e.g. Invoices 2026" />
    </label>
    <div class="modal-actions">
      <button class="btn primary" id="confirm-new-folder">Create Folder</button>
    </div>
  `);
  $("confirm-new-folder").onclick = async () => {
    const name = $("new-folder-name").value.trim();
    if (!name) return;
    try {
      await api("/api/folders", {
        method: "POST",
        body: {
          name,
          parentId: (state.selectedFolderId && state.selectedFolderId !== "root") ? Number(state.selectedFolderId) : null
        }
      });
      closeModal();
      showToast("Folder created");
      loadFolders();
    } catch (err) {
      alert(err.message);
    }
  };
};

$("file-input").onchange = (e) => uploadFiles(e.target.files);
const dz = $("drop-zone");
dz.ondragover = (e) => { e.preventDefault(); dz.classList.add("dragover"); };
dz.ondragleave = () => dz.classList.remove("dragover");
dz.ondrop = (e) => {
  e.preventDefault();
  dz.classList.remove("dragover");
  uploadFiles(e.dataTransfer.files);
};

let searchTimer;
$("search-input").oninput = (e) => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    state.searchQuery = e.target.value.trim();
    loadFiles();
  }, 200);
};
$("sort-select").onchange = (e) => {
  state.sort = e.target.value;
  loadFiles();
};
$("category-pills").querySelectorAll(".pill").forEach((pill) => {
  pill.onclick = () => {
    $("category-pills").querySelectorAll(".pill").forEach((p) => p.classList.remove("active"));
    pill.classList.add("active");
    state.selectedCategory = pill.dataset.cat;
    loadFiles();
  };
});

$("btn-refresh-storage").onclick = loadStorageView;
$("btn-refresh-admin").onclick = loadAdminView;
$("modal-close").onclick = closeModal;
$("modal-backdrop").onclick = (e) => { if (e.target === $("modal-backdrop")) closeModal(); };

initSession();

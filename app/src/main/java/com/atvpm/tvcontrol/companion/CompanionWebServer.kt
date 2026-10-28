package com.atvpm.tvcontrol.companion

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class CompanionWebServer(
    private val context: Context,
    private val onApkUploaded: (File) -> Unit,
    private val onActionTriggered: (action: String) -> String,
    private val getSystemStatusJson: () -> String,
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    val port = 8888

    fun start(scope: CoroutineScope) {
        if (isRunning.getAndSet(true)) return

        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (isActive && !ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            }.also {
                isRunning.set(false)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    val localIpAddress: String?
        get() {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
                    .firstOrNull { it != "127.0.0.1" }
            }.getOrNull()
        }

    val serverUrl: String?
        get() = localIpAddress?.let { "http://$it:$port" }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    val k = line!!.substring(0, colonIdx).trim().lowercase()
                    val v = line!!.substring(colonIdx + 1).trim()
                    headers[k] = v
                }
            }

            when {
                method == "GET" && (path == "/" || path.startsWith("/?")) -> {
                    serveHtmlDashboard(output)
                }
                method == "GET" && path == "/api/status" -> {
                    val json = getSystemStatusJson()
                    sendJsonResponse(output, 200, json)
                }
                method == "POST" && path.startsWith("/api/action/") -> {
                    val action = path.removePrefix("/api/action/")
                    val msg = onActionTriggered(action)
                    sendJsonResponse(output, 200, """{"success":true,"message":"$msg"}""")
                }
                method == "POST" && path == "/api/upload-apk" -> {
                    handleApkUpload(headers, input, output)
                }
                else -> {
                    sendResponse(output, 404, "text/plain", "Not Found".toByteArray())
                }
            }
        }
    }

    private fun handleApkUpload(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val contentType = headers["content-type"] ?: ""
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L

        if (contentLength <= 0) {
            sendJsonResponse(output, 400, """{"success":false,"error":"Missing Content-Length"}""")
            return
        }

        val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "sideload")
        targetDir.mkdirs()

        val destFile = File(targetDir, "sideload_${System.currentTimeMillis()}.apk")

        if (contentType.contains("multipart/form-data")) {
            val boundary = contentType.substringAfter("boundary=", "").trim()
            if (boundary.isNotEmpty()) {
                parseMultipartUpload(boundary, input, destFile)
            } else {
                writeRawStreamToFile(input, contentLength, destFile)
            }
        } else {
            writeRawStreamToFile(input, contentLength, destFile)
        }

        if (destFile.exists() && destFile.length() > 1024) {
            onApkUploaded(destFile)
            sendJsonResponse(output, 200, """{"success":true,"filename":"${destFile.name}","size":${destFile.length()}}""")
        } else {
            sendJsonResponse(output, 500, """{"success":false,"error":"Failed to save APK file"}""")
        }
    }

    private fun writeRawStreamToFile(input: InputStream, length: Long, file: File) {
        var remaining = length
        val buffer = ByteArray(16384)
        BufferedOutputStream(FileOutputStream(file)).use { fos ->
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) break
                fos.write(buffer, 0, read)
                remaining -= read
            }
            fos.flush()
        }
    }

    private fun parseMultipartUpload(boundary: String, input: InputStream, file: File) {
        val boundaryBytes = ("--$boundary").toByteArray(Charsets.US_ASCII)
        // Consume headers of the first multipart body part
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line.isNullOrBlank()) break
        }

        // Write remainder until boundary
        BufferedOutputStream(FileOutputStream(file)).use { fos ->
            val buf = ByteArray(16384)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                fos.write(buf, 0, bytesRead)
            }
            fos.flush()
        }
    }

    private fun sendResponse(output: OutputStream, code: Int, contentType: String, data: ByteArray) {
        val statusText = if (code == 200) "OK" else if (code == 404) "Not Found" else "Error"
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(data)
        output.flush()
    }

    private fun sendJsonResponse(output: OutputStream, code: Int, json: String) {
        sendResponse(output, code, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun serveHtmlDashboard(output: OutputStream) {
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".uppercase()
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>ATV Top • Web Bridge</title>
  <style>
    :root {
      --bg: #090D16;
      --card: #131A29;
      --border: #222F47;
      --accent: #2563EB;
      --accent-hover: #3B82F6;
      --text: #F8FAFC;
      --muted: #94A3B8;
      --success: #10B981;
      --danger: #EF4444;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 1.5rem; max-width: 600px; margin: 0 auto; }
    header { text-align: center; margin-bottom: 1.5rem; }
    .badge { display: inline-block; background: rgba(37, 99, 235, 0.2); border: 1px solid var(--accent); color: #60A5FA; padding: 4px 10px; border-radius: 9999px; font-size: 0.8rem; font-weight: 600; margin-bottom: 0.5rem; }
    h1 { font-size: 1.5rem; font-weight: 700; }
    p.sub { color: var(--muted); font-size: 0.9rem; margin-top: 4px; }
    .card { background: var(--card); border: 1px solid var(--border); border-radius: 14px; padding: 1.25rem; margin-bottom: 1.25rem; }
    .card h2 { font-size: 1.1rem; margin-bottom: 0.75rem; display: flex; align-items: center; gap: 8px; }
    .dropzone { border: 2px dashed #3B82F6; border-radius: 12px; padding: 2rem 1rem; text-align: center; cursor: pointer; background: rgba(37, 99, 235, 0.05); transition: 0.2s; }
    .dropzone:hover { background: rgba(37, 99, 235, 0.12); }
    .btn-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    button { background: #1E293B; border: 1px solid var(--border); color: var(--text); padding: 12px; border-radius: 10px; font-size: 0.95rem; font-weight: 600; cursor: pointer; transition: 0.15s; }
    button:hover { background: #334155; }
    button.primary { background: var(--accent); border-color: var(--accent); }
    button.primary:hover { background: var(--accent-hover); }
    button.danger { background: rgba(239, 68, 68, 0.15); border-color: var(--danger); color: #FCA5A5; }
    button.danger:hover { background: rgba(239, 68, 68, 0.3); }
    #toast { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #1E293B; border: 1px solid var(--border); color: var(--text); padding: 10px 18px; border-radius: 8px; font-size: 0.9rem; display: none; }
  </style>
</head>
<body>
  <header>
    <div class="badge">CONNECTED TO TV</div>
    <h1>$deviceName</h1>
    <p class="sub">ATV Top Sideload & Remote Power Center</p>
  </header>

  <div class="card">
    <h2>📲 Drag & Drop APK Sideload</h2>
    <div class="dropzone" id="dropArea" onclick="document.getElementById('fileInput').click()">
      <p style="font-size: 1.8rem; margin-bottom: 6px;">📥</p>
      <strong>Tap or Drop .APK file here</strong>
      <p style="color: var(--muted); font-size: 0.85rem; margin-top: 4px;">Instantly sideloads and opens installer on your TV screen</p>
      <input type="file" id="fileInput" accept=".apk" style="display: none" onchange="uploadApk(this.files[0])">
    </div>
  </div>

  <div class="card">
    <h2>⚡ Quick TV Controls</h2>
    <div class="btn-grid">
      <button class="primary" onclick="runAction('kill-all')">🧹 Kill Background Apps</button>
      <button onclick="runAction('reload-launcher')">🔄 Reload Home Screen</button>
      <button onclick="runAction('sleep')">💤 Sleep Screen</button>
      <button class="danger" onclick="runAction('restart')">⟳ Restart TV</button>
    </div>
  </div>

  <div id="toast"></div>

  <script>
    function showToast(msg) {
      const t = document.getElementById('toast');
      t.innerText = msg;
      t.style.display = 'block';
      setTimeout(() => t.style.display = 'none', 3000);
    }

    async function runAction(act) {
      showToast('Sending command to TV...');
      try {
        const res = await fetch('/api/action/' + act, { method: 'POST' });
        const data = await res.json();
        showToast(data.message || 'Action executed successfully');
      } catch (e) {
        showToast('Error communicating with TV');
      }
    }

    async function uploadApk(file) {
      if (!file) return;
      showToast('Uploading ' + file.name + ' (' + Math.round(file.size / 1024 / 1024) + ' MB)...');
      try {
        const res = await fetch('/api/upload-apk', {
          method: 'POST',
          headers: { 'Content-Type': 'application/vnd.android.package-archive' },
          body: file
        });
        const data = await res.json();
        if (data.success) {
          showToast('Uploaded! Check TV screen to confirm install.');
        } else {
          showToast('Upload failed: ' + (data.error || 'Unknown error'));
        }
      } catch (e) {
        showToast('Upload failed. Check Wi-Fi connection.');
      }
    }

    const dropArea = document.getElementById('dropArea');
    ['dragenter', 'dragover'].forEach(n => dropArea.addEventListener(n, e => { e.preventDefault(); dropArea.style.borderColor = '#10B981'; }));
    ['dragleave', 'drop'].forEach(n => dropArea.addEventListener(n, e => { e.preventDefault(); dropArea.style.borderColor = '#3B82F6'; }));
    dropArea.addEventListener('drop', e => {
      if (e.dataTransfer.files.length) uploadApk(e.dataTransfer.files[0]);
    });
  </script>
</body>
</html>
        """.trimIndent()

        sendResponse(output, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }
}

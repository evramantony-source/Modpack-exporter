package com.evramantony.modpackexporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val pickCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }
        val title = TextView(this).apply {
            text = "MODPACK EXPORTER"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Phase 1 • Import & inspect ZIP / MRPACK"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 32)
        }
        val pick = Button(this).apply {
            text = "📦  SELECT MODPACK"
            setOnClickListener { openPicker() }
        }
        status = TextView(this).apply {
            text = "Select a .zip or .mrpack file to begin."
            textSize = 16f
            setPadding(0, 32, 0, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(pick)
        root.addView(status)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        startActivityForResult(intent, pickCode)
    }

    @Deprecated("Activity result API kept minimal for Phase 1")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        inspect(uri)
    }

    private fun inspect(uri: Uri) {
        status.text = "Inspecting…"
        Thread {
            val result = runCatching { inspectZip(uri) }.fold(
                onSuccess = { it },
                onFailure = { "❌ Could not inspect archive: ${it.message ?: "unknown error"}" }
            )
            runOnUiThread { status.text = result }
        }.start()
    }

    private fun inspectZip(uri: Uri): String {
        val name = queryName(uri) ?: "selected file"
        var entries = 0
        var hasMrpackIndex = false
        var hasCurseManifest = false
        var hasMods = false
        var hasConfig = false
        var hasOverrides = false
        var unsafe = false
        var totalUncompressed = 0L

        contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    entries++
                    val path = entry.name.replace('\\', '/')
                    if (path.startsWith("/") || path.split('/').contains("..") || Regex("^[A-Za-z]:").containsMatchIn(path)) {
                        unsafe = true
                    }
                    if (entry.isDirectory) continue
                    val rootPath = path.substringAfterLast('/', path)
                    if (path == "modrinth.index.json" || path.endsWith("/modrinth.index.json")) hasMrpackIndex = true
                    if (path == "manifest.json" || path.endsWith("/manifest.json")) hasCurseManifest = true
                    if (path.startsWith("mods/") || path.contains("/mods/")) hasMods = true
                    if (path.startsWith("config/") || path.contains("/config/")) hasConfig = true
                    if (path.startsWith("overrides/") || path.contains("/overrides/")) hasOverrides = true
                    var read = 0L
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n <= 0) break
                        read += n
                        if (read > 512L * 1024L * 1024L || totalUncompressed > 2L * 1024L * 1024L * 1024L) {
                            throw IllegalArgumentException("Archive expands beyond the Phase 1 safety limit")
                        }
                    }
                    totalUncompressed += read
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalArgumentException("Unable to open the selected file")

        val type = when {
            hasMrpackIndex || name.lowercase().endsWith(".mrpack") -> "Modrinth MRPACK"
            hasCurseManifest -> "CurseForge-style ZIP"
            else -> "Generic ZIP"
        }
        val warning = if (unsafe) "\n⚠️ Unsafe archive paths detected." else ""
        return buildString {
            append("✅ Archive opened successfully\n\n")
            append("File: $name\n")
            append("Detected: $type\n")
            append("Entries: $entries\n")
            append("Mods: ${if (hasMods) "found" else "not found"}\n")
            append("Config: ${if (hasConfig) "found" else "not found"}\n")
            append("Overrides: ${if (hasOverrides) "found" else "not found"}\n")
            append("Uncompressed data: ${totalUncompressed / (1024 * 1024)} MiB")
            append(warning)
            append("\n\nPhase 1 import engine is working. Exporters come next.")
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }
}

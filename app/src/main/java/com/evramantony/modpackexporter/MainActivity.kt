package com.evramantony.modpackexporter

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var selectedUri: Uri? = null
    private val pickCode = 1001
    private val saveCode = 1002
    private var pendingExport: ExportType? = null
    private var pendingMetadata: ModpackExporter.Metadata? = null

    private enum class ExportType { MRPACK, ZIP }

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
            text = "Phase 2 • Real export engine"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 32)
        }
        val pick = Button(this).apply {
            text = "📦  SELECT MODPACK"
            setOnClickListener { openPicker() }
        }
        val mrpack = Button(this).apply {
            text = "EXPORT AS MODRINTH .MRPACK"
            isEnabled = false
            setOnClickListener { beginMrpackExport() }
        }
        val zip = Button(this).apply {
            text = "EXPORT AS NORMAL .ZIP"
            isEnabled = false
            setOnClickListener { beginExport(ExportType.ZIP) }
        }
        val curse = Button(this).apply {
            text = "EXPORT AS CURSEFORGE ZIP (NEXT)"
            isEnabled = false
        }
        status = TextView(this).apply {
            text = "Select a .zip or .mrpack file to begin."
            textSize = 16f
            setPadding(0, 32, 0, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(pick)
        root.addView(mrpack)
        root.addView(zip)
        root.addView(curse)
        root.addView(status)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        exportButtons = listOf(mrpack, zip)
    }

    private lateinit var exportButtons: List<Button>

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
        }
        startActivityForResult(intent, pickCode)
    }

    @Deprecated("Activity result API kept compatible with Phase 1")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selectedUri = uri
            inspect(uri)
        } else if (requestCode == saveCode && resultCode == RESULT_OK && data?.data != null) {
            saveExport(data.data!!)
        }
    }

    private fun inspect(uri: Uri) {
        status.text = "Inspecting…"
        Thread {
            val result = runCatching { inspectZip(uri) }.fold(
                onSuccess = { it },
                onFailure = { "❌ Could not inspect archive: ${it.message ?: "unknown error"}" }
            )
            runOnUiThread {
                status.text = result
                val ok = result.startsWith("✅")
                exportButtons.forEach { it.isEnabled = ok }
            }
        }.start()
    }

    private fun beginMrpackExport() {
        val name = queryName(selectedUri!!)?.substringBeforeLast('.')?.ifBlank { "My Modpack" } ?: "My Modpack"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        val nameInput = EditText(this).apply { setText(name); hint = "Pack name" }
        val mcInput = EditText(this).apply { hint = "Minecraft version (e.g. 1.21.1)" }
        val loaderInput = EditText(this).apply { hint = "Loader (fabric / forge / neoforge / quilt)" }
        val loaderVersionInput = EditText(this).apply { hint = "Loader version (optional)" }
        layout.addView(nameInput)
        layout.addView(mcInput)
        layout.addView(loaderInput)
        layout.addView(loaderVersionInput)
        AlertDialog.Builder(this)
            .setTitle("MRPACK metadata")
            .setMessage("Generic ZIP files need Minecraft metadata. Existing MRPACK files keep their index.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val loader = loaderInput.text.toString().trim().lowercase()
                if (nameInput.text.isBlank() || mcInput.text.isBlank() || loader.isBlank()) {
                    status.text = "❌ Pack name, Minecraft version, and loader are required."
                    return@setPositiveButton
                }
                pendingMetadata = ModpackExporter.Metadata(
                    nameInput.text.toString().trim(),
                    mcInput.text.toString().trim(),
                    loader,
                    loaderVersionInput.text.toString().trim()
                )
                beginExport(ExportType.MRPACK)
            }
            .show()
    }

    private fun beginExport(exportType: ExportType) {
        if (selectedUri == null) {
            status.text = "❌ Select a modpack first."
            return
        }
        pendingExport = exportType
        val base = queryName(selectedUri!!) ?: "modpack.zip"
        val name = when (exportType) {
            ExportType.MRPACK -> base.substringBeforeLast('.') + ".mrpack"
            ExportType.ZIP -> base.substringBeforeLast('.') + "-export.zip"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, saveCode)
    }

    private fun saveExport(destination: Uri) {
        val inputUri = selectedUri ?: return
        val type = pendingExport ?: return
        status.text = "Exporting…"
        exportButtons.forEach { it.isEnabled = false }
        Thread {
            val result = runCatching {
                contentResolver.openInputStream(inputUri)?.use { input ->
                    contentResolver.openOutputStream(destination)?.use { output ->
                        when (type) {
                            ExportType.MRPACK -> ModpackExporter.exportMrpack(input, output, pendingMetadata!!)
                            ExportType.ZIP -> ModpackExporter.exportZip(input, output)
                        }
                    } ?: throw IllegalArgumentException("Unable to create output file")
                } ?: throw IllegalArgumentException("Unable to open selected file")

                if (type == ExportType.MRPACK) {
                    contentResolver.openInputStream(destination)?.use { ModpackExporter.validateMrpack(it) }
                        ?: throw IllegalArgumentException("Unable to verify exported MRPACK")
                }
                "✅ Export complete!\n\nSaved successfully and verified."
            }.fold(
                onSuccess = { it },
                onFailure = { "❌ Export failed: ${it.message ?: "unknown error"}" }
            )
            runOnUiThread {
                status.text = result
                exportButtons.forEach { it.isEnabled = selectedUri != null }
            }
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
                    if (path.startsWith("/") || path.split('/').contains("..") || Regex("^[A-Za-z]:").containsMatchIn(path)) unsafe = true
                    if (entry.isDirectory) continue
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
                            throw IllegalArgumentException("Archive expands beyond the safety limit")
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
        return buildString {
            append("✅ Archive opened successfully\n\n")
            append("File: $name\nDetected: $type\nEntries: $entries\n")
            append("Mods: ${if (hasMods) "found" else "not found"}\n")
            append("Config: ${if (hasConfig) "found" else "not found"}\n")
            append("Overrides: ${if (hasOverrides) "found" else "not found"}\n")
            append("Uncompressed data: ${totalUncompressed / (1024 * 1024)} MiB")
            if (unsafe) append("\n⚠️ Unsafe archive paths detected.")
            append("\n\nReady for export.")
        }
    }

    private fun queryName(uri: Uri?): String? {
        if (uri == null) return null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }
}

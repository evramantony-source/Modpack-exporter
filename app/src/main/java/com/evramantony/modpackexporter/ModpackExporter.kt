package com.evramantony.modpackexporter

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ModpackExporter {
    data class Metadata(
        val name: String,
        val minecraftVersion: String,
        val loader: String,
        val loaderVersion: String = ""
    )

    fun exportMrpack(input: InputStream, output: OutputStream, metadata: Metadata) {
        require(metadata.name.isNotBlank()) { "Pack name is required" }
        require(metadata.minecraftVersion.isNotBlank()) { "Minecraft version is required" }
        require(metadata.loader.isNotBlank()) { "Loader is required" }

        val entries = readSafeEntries(input)
        val existingIndex = entries.firstOrNull { it.path == "modrinth.index.json" }

        ZipOutputStream(BufferedOutputStream(output)).use { out ->
            if (existingIndex != null) {
                // A real MRPACK already contains its authoritative index. Preserve it,
                // while rebuilding the archive with deterministic safe paths.
                for (entry in entries) {
                    put(out, entry.path, entry.data)
                }
                return
            }

            val index = JSONObject()
                .put("formatVersion", 1)
                .put("game", "minecraft")
                .put("versionId", slug(metadata.name))
                .put("name", metadata.name)
                .put("files", JSONArray())
                .put("dependencies", JSONObject()
                    .put("minecraft", metadata.minecraftVersion)
                    .put(metadata.loader, metadata.loaderVersion.ifBlank { "unknown" }))

            put(out, "modrinth.index.json", index.toString(2).toByteArray(Charsets.UTF_8))

            // Generic ZIP content is bundled through overrides so the resulting
            // MRPACK remains self-contained instead of inventing download URLs/hashes.
            for (entry in entries) {
                if (entry.path == "manifest.json" || entry.path == "modrinth.index.json") continue
                put(out, "overrides/${entry.path}", entry.data)
            }
        }
    }

    fun exportZip(input: InputStream, output: OutputStream) {
        val entries = readSafeEntries(input)
        ZipOutputStream(BufferedOutputStream(output)).use { out ->
            for (entry in entries) put(out, entry.path, entry.data)
        }
    }

    fun validateMrpack(input: InputStream): String {
        val entries = readSafeEntries(input)
        val index = entries.firstOrNull { it.path == "modrinth.index.json" }
            ?: throw IllegalArgumentException("Missing root modrinth.index.json")
        val json = JSONObject(String(index.data, Charsets.UTF_8))
        require(json.optInt("formatVersion", -1) == 1) { "Unsupported MRPACK formatVersion" }
        require(json.optString("game") == "minecraft") { "MRPACK game must be minecraft" }
        require(json.has("files")) { "MRPACK index is missing files" }
        require(json.has("dependencies")) { "MRPACK index is missing dependencies" }
        return "MRPACK is structurally valid"
    }

    private data class Entry(val path: String, val data: ByteArray)

    private fun readSafeEntries(input: InputStream): List<Entry> {
        val result = ArrayList<Entry>()
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val path = normalize(e.name)
                if (path.isEmpty() || e.isDirectory) continue
                if (path.startsWith("/") || path.split('/').contains("..") || Regex("^[A-Za-z]:").containsMatchIn(path)) {
                    throw IllegalArgumentException("Unsafe archive path: ${e.name}")
                }
                val bytes = zip.readBytesLimited(512L * 1024L * 1024L)
                total += bytes.size
                if (total > 2L * 1024L * 1024L * 1024L) {
                    throw IllegalArgumentException("Archive expands beyond the 2 GiB safety limit")
                }
                result += Entry(path, bytes)
                zip.closeEntry()
            }
        }
        return result
    }

    private fun ZipInputStream.readBytesLimited(limit: Long): ByteArray {
        val buffer = ByteArray(32 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw IllegalArgumentException("Archive entry exceeds 512 MiB safety limit")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trimStart('/')

    private fun put(out: ZipOutputStream, path: String, data: ByteArray) {
        out.putNextEntry(ZipEntry(path))
        out.write(data)
        out.closeEntry()
    }

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "modpack" }
}

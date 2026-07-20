package com.example.songbook

import com.example.songbook.domain.model.Song
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class GoogleDocSyncResult(
    val documentId: String,
    val documentUrl: String,
    val revisionId: String?,
    val folderId: String
)

data class GoogleFolderSyncResult(
    val folderId: String,
    val importedSongs: List<Song>
)

object GoogleDocsSyncService {
    const val DOCS_SCOPE = "https://www.googleapis.com/auth/documents"
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    private const val FOLDER_NAME = "Prestissimo Songbook"
    private const val DOC_MIME_TYPE = "application/vnd.google-apps.document"
    private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

    fun createOrUpdate(song: Song, accessToken: String): GoogleDocSyncResult {
        val folderId = ensureSongbookFolder(accessToken)
        val content = exportSongToDocument(song)
        return if (song.googleDocId.isBlank()) {
            createDocument(song.title.ifBlank { "Untitled song" }, content, accessToken, folderId)
        } else {
            updateDocument(song.googleDocId, song.title.ifBlank { "Untitled song" }, content, accessToken, folderId)
        }
    }

    fun syncFolder(accessToken: String): GoogleFolderSyncResult {
        val folderId = ensureSongbookFolder(accessToken)
        val docs = listSongDocuments(folderId, accessToken)
        return GoogleFolderSyncResult(
            folderId = folderId,
            importedSongs = docs.map { importDocument(it, accessToken) }
        )
    }

    private fun createDocument(title: String, content: String, accessToken: String, folderId: String): GoogleDocSyncResult {
        val response = requestJson(
            method = "POST",
            url = "https://docs.googleapis.com/v1/documents",
            accessToken = accessToken,
            body = JSONObject().put("title", title)
        )
        val documentId = response.getString("documentId")
        moveFileToFolder(documentId, folderId, accessToken)
        val updated = updateDocument(documentId, title, content, accessToken, folderId)
        return updated.copy(folderId = folderId)
    }

    private fun updateDocument(
        documentId: String,
        title: String,
        content: String,
        accessToken: String,
        folderId: String
    ): GoogleDocSyncResult {
        renameDriveFile(documentId, title, accessToken)
        moveFileToFolder(documentId, folderId, accessToken)
        val document = requestJson(
            method = "GET",
            url = "https://docs.googleapis.com/v1/documents/$documentId",
            accessToken = accessToken
        )
        val bodyContent = document.optJSONObject("body")?.optJSONArray("content") ?: JSONArray()
        val endIndex = bodyContent.optJSONObject(bodyContent.length() - 1)?.optInt("endIndex", 1) ?: 1
        val requests = JSONArray()
        if (endIndex > 1) {
            requests.put(
                JSONObject().put(
                    "deleteContentRange",
                    JSONObject().put(
                        "range",
                        JSONObject()
                            .put("startIndex", 1)
                            .put("endIndex", endIndex - 1)
                    )
                )
            )
        }
        if (content.isNotBlank()) {
            requests.put(
                JSONObject().put(
                    "insertText",
                    JSONObject()
                        .put("location", JSONObject().put("index", 1))
                        .put("text", content)
                )
            )
        }
        val batchUpdate = requestJson(
            method = "POST",
            url = "https://docs.googleapis.com/v1/documents/$documentId:batchUpdate",
            accessToken = accessToken,
            body = JSONObject().put("requests", requests)
        )
        return GoogleDocSyncResult(
            documentId = documentId,
            documentUrl = "https://docs.google.com/document/d/$documentId/edit",
            revisionId = batchUpdate.optJSONObject("writeControl")?.optString("requiredRevisionId"),
            folderId = folderId
        )
    }

    private fun ensureSongbookFolder(accessToken: String): String {
        val escapedName = FOLDER_NAME.replace("\\", "\\\\").replace("'", "\\'")
        val query = "mimeType = '$FOLDER_MIME_TYPE' and name = '$escapedName' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val response = requestJson(
            method = "GET",
            url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name)&spaces=drive",
            accessToken = accessToken
        )
        val files = response.optJSONArray("files") ?: JSONArray()
        val existingId = files.optJSONObject(0)?.optString("id").orEmpty()
        if (existingId.isNotBlank()) {
            return existingId
        }
        val created = requestJson(
            method = "POST",
            url = "https://www.googleapis.com/drive/v3/files?fields=id",
            accessToken = accessToken,
            body = JSONObject()
                .put("name", FOLDER_NAME)
                .put("mimeType", FOLDER_MIME_TYPE)
        )
        return created.getString("id")
    }

    private fun listSongDocuments(folderId: String, accessToken: String): List<JSONObject> {
        val query = "'$folderId' in parents and mimeType = '$DOC_MIME_TYPE' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val response = requestJson(
            method = "GET",
            url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,webViewLink,modifiedTime)&orderBy=modifiedTime desc&spaces=drive",
            accessToken = accessToken
        )
        val files = response.optJSONArray("files") ?: JSONArray()
        return buildList {
            for (i in 0 until files.length()) {
                files.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    private fun importDocument(file: JSONObject, accessToken: String): Song {
        val documentId = file.getString("id")
        val document = requestJson(
            method = "GET",
            url = "https://docs.googleapis.com/v1/documents/$documentId",
            accessToken = accessToken
        )
        val flattened = flattenDocumentText(document)
        val song = Song()
        song.googleDocId = documentId
        song.googleDocUrl = file.optString("webViewLink").ifBlank { "https://docs.google.com/document/d/$documentId/edit" }
        song.lastKnownDocRevisionId = document.optString("revisionId")
        song.lastSyncedAt = System.currentTimeMillis()
        song.isOnlineSource = false
        hydrateSongFromDocument(song, file.optString("name"), flattened)
        song.lastSyncedBodyHash = song.body.hashCode().toString()
        return song
    }

    private fun hydrateSongFromDocument(song: Song, title: String, rawText: String) {
        val lines = rawText.replace("\r\n", "\n").split("\n").toMutableList()
        song.title = title.ifBlank { "Untitled song" }
        if (lines.isNotEmpty() && lines.first().trim() == song.title.trim()) {
            lines.removeAt(0)
        }
        while (lines.isNotEmpty() && lines.first().isBlank()) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty()
            && !lines.first().startsWith("Key:")
            && !lines.first().startsWith("Time Signature:")
            && !lines.first().startsWith("Capo:")
            && !lines.first().startsWith("Tuning:")
            && !lines.first().startsWith("Notes:")
        ) {
            song.artist = lines.removeAt(0).trim()
        }
        if (lines.isNotEmpty() && lines.first().startsWith("Key:")) {
            song.key = lines.removeAt(0).removePrefix("Key:").trim()
        }
        if (lines.isNotEmpty() && lines.first().startsWith("Time Signature:")) {
            song.timeSignature = lines.removeAt(0).removePrefix("Time Signature:").trim()
        }
        if (lines.isNotEmpty() && lines.first().startsWith("Capo:")) {
            song.capo = lines.removeAt(0).removePrefix("Capo:").trim()
        }
        if (lines.isNotEmpty() && lines.first().startsWith("Tuning:")) {
            song.tuning = lines.removeAt(0).removePrefix("Tuning:").trim()
        }
        if (lines.isNotEmpty() && lines.first().startsWith("Notes:")) {
            song.notes = lines.removeAt(0).removePrefix("Notes:").trim()
        }
        while (lines.isNotEmpty() && lines.first().isBlank()) {
            lines.removeAt(0)
        }
        song.body = lines.joinToString("\n").trimEnd()
    }

    private fun flattenDocumentText(document: JSONObject): String {
        val builder = StringBuilder()
        val content = document.optJSONObject("body")?.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            val paragraph = item.optJSONObject("paragraph")
            if (paragraph != null) {
                val elements = paragraph.optJSONArray("elements") ?: JSONArray()
                for (j in 0 until elements.length()) {
                    val textRun = elements.optJSONObject(j)?.optJSONObject("textRun") ?: continue
                    builder.append(textRun.optString("content"))
                }
            }
        }
        return builder.toString().trimEnd()
    }

    private fun moveFileToFolder(fileId: String, folderId: String, accessToken: String) {
        val metadata = requestJson(
            method = "GET",
            url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=parents",
            accessToken = accessToken
        )
        val parents = metadata.optJSONArray("parents") ?: JSONArray()
        val currentParents = buildList {
            for (i in 0 until parents.length()) {
                parents.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        if (currentParents.size == 1 && currentParents.first() == folderId) {
            return
        }
        val addParents = URLEncoder.encode(folderId, "UTF-8")
        val removeParents = URLEncoder.encode(currentParents.joinToString(","), "UTF-8")
        val suffix = buildString {
            append("?addParents=$addParents")
            if (currentParents.isNotEmpty()) {
                append("&removeParents=$removeParents")
            }
        }
        requestJson(
            method = "PATCH",
            url = "https://www.googleapis.com/drive/v3/files/$fileId$suffix",
            accessToken = accessToken,
            body = JSONObject()
        )
    }

    private fun renameDriveFile(documentId: String, title: String, accessToken: String) {
        requestJson(
            method = "PATCH",
            url = "https://www.googleapis.com/drive/v3/files/$documentId",
            accessToken = accessToken,
            body = JSONObject().put("name", title)
        )
    }

    private fun requestJson(
        method: String,
        url: String,
        accessToken: String,
        body: JSONObject? = null
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        body?.let {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(it.toString())
            }
        }
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val payload = BufferedReader(InputStreamReader(stream)).use { reader ->
            buildString {
                var line: String? = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
        }
        if (responseCode !in 200..299) {
            throw IllegalStateException(payload.ifBlank { "HTTP $responseCode" })
        }
        return if (payload.isBlank()) JSONObject() else JSONObject(payload)
    }

    private fun exportSongToDocument(song: Song): String {
        return buildString {
            appendLine(song.title.ifBlank { "Untitled song" })
            if (song.artist.isNotBlank()) {
                appendLine(song.artist)
            }
            if (song.key.isNotBlank()) {
                appendLine("Key: ${song.key}")
            }
            if (song.timeSignature.isNotBlank()) {
                appendLine("Time Signature: ${song.timeSignature}")
            }
            if (song.capo.isNotBlank()) {
                appendLine("Capo: ${song.capo}")
            }
            if (song.tuning.isNotBlank()) {
                appendLine("Tuning: ${song.tuning}")
            }
            if (song.notes.isNotBlank()) {
                appendLine("Notes: ${song.notes}")
            }
            appendLine()
            append(song.body)
            if (song.body.isNotBlank()) {
                appendLine()
            }
        }.trimEnd()
    }
}

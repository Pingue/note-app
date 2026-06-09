package app.pennotes.sync

import android.content.Context
import app.pennotes.storage.DocumentRepository
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Outcome of a sync pass, surfaced to the UI as a short status line. */
data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val error: String? = null,
)

/**
 * Two-way sync between the local document store and a "PenNotes" folder in the
 * user's Google Drive. Each document is a *sub-folder* (containing the
 * `index.pennotes` manifest and one SVG per page), tagged with the document id
 * and modification time in Drive appProperties. Conflicts resolve last-write-
 * wins per document; the app is fully usable offline.
 *
 * Uses the drive.file scope (access only to files this app creates). See the
 * README for the Google Cloud OAuth setup required to enable sync.
 */
class DriveSync(private val context: Context, private val repo: DocumentRepository) {

    private val http = OkHttpClient()

    fun signInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun currentAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(): Boolean = currentAccount() != null

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: return@withContext SyncResult(error = "Not signed in")
        val androidAccount = account.account ?: return@withContext SyncResult(error = "No Google account")

        try {
            val token = GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_FILE_SCOPE")
            val rootId = ensureRootFolder(token)
            val remote = listRemoteDocs(rootId, token).associateBy { it.docId }

            var uploaded = 0
            var downloaded = 0

            val locals = repo.list()
            val localIds = locals.map { it.id }.toSet()

            for (doc in locals) {
                val match = remote[doc.id]
                when {
                    match == null -> {
                        val folderId = createFolder(rootId, doc.title, doc.id, doc.updatedAt, token)
                        pushFiles(doc.id, folderId, token)
                        uploaded++
                    }
                    doc.updatedAt > match.updatedAt -> {
                        pushFiles(doc.id, match.folderId, token)
                        updateFolderMeta(match.folderId, doc.title, doc.updatedAt, token)
                        uploaded++
                    }
                    match.updatedAt > doc.updatedAt -> {
                        pullFiles(doc.id, match.folderId, token)
                        downloaded++
                    }
                }
            }

            // Documents that only exist remotely get pulled down.
            for ((id, file) in remote) {
                if (id !in localIds) {
                    pullFiles(id, file.folderId, token)
                    downloaded++
                }
            }

            SyncResult(uploaded = uploaded, downloaded = downloaded)
        } catch (e: Exception) {
            SyncResult(error = e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- Drive model helpers ----------------------------------------------

    private data class RemoteDoc(val folderId: String, val docId: String, val updatedAt: Long)

    private fun ensureRootFolder(token: String): String {
        val q = "mimeType='$FOLDER_MIME' and name='$ROOT_NAME' and trashed=false"
        val body = get("$DRIVE_API/files?q=${enc(q)}&fields=files(id)&spaces=drive", token)
        JSONObject(body).optJSONArray("files")?.let { if (it.length() > 0) return it.getJSONObject(0).getString("id") }
        val meta = JSONObject().put("name", ROOT_NAME).put("mimeType", FOLDER_MIME)
        val created = post("$DRIVE_API/files?fields=id", token, meta.toString().toRequestBody(JSON_MEDIA))
        return JSONObject(created).getString("id")
    }

    private fun listRemoteDocs(rootId: String, token: String): List<RemoteDoc> {
        val q = "'$rootId' in parents and mimeType='$FOLDER_MIME' and trashed=false"
        val body = get("$DRIVE_API/files?q=${enc(q)}&fields=files(id,appProperties)&spaces=drive", token)
        val arr = JSONObject(body).optJSONArray("files") ?: return emptyList()
        val out = mutableListOf<RemoteDoc>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val props = f.optJSONObject("appProperties") ?: continue
            val docId = props.optString("documentId")
            if (docId.isEmpty()) continue
            val updated = props.optString("updatedAt").toLongOrNull() ?: 0L
            out.add(RemoteDoc(f.getString("id"), docId, updated))
        }
        return out
    }

    private fun createFolder(parentId: String, title: String, docId: String, updatedAt: Long, token: String): String {
        val meta = JSONObject()
            .put("name", title)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
            .put("appProperties", JSONObject().put("documentId", docId).put("updatedAt", updatedAt.toString()))
        val created = post("$DRIVE_API/files?fields=id", token, meta.toString().toRequestBody(JSON_MEDIA))
        return JSONObject(created).getString("id")
    }

    private fun updateFolderMeta(folderId: String, title: String, updatedAt: Long, token: String) {
        val meta = JSONObject()
            .put("name", title)
            .put("appProperties", JSONObject().put("updatedAt", updatedAt.toString()))
        patch("$DRIVE_API/files/$folderId?fields=id", token, meta.toString().toRequestBody(JSON_MEDIA))
    }

    private fun listFilesInFolder(folderId: String, token: String): Map<String, String> {
        val q = "'$folderId' in parents and mimeType!='$FOLDER_MIME' and trashed=false"
        val body = get("$DRIVE_API/files?q=${enc(q)}&fields=files(id,name)&spaces=drive", token)
        val arr = JSONObject(body).optJSONArray("files") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            out[f.getString("name")] = f.getString("id")
        }
        return out
    }

    private fun pushFiles(docId: String, folderId: String, token: String) {
        val remoteFiles = listFilesInFolder(folderId, token)
        val localFiles = repo.filesFor(docId)
        val localNames = localFiles.map { it.name }.toSet()

        for (file in localFiles) {
            val mime = mimeFor(file.name)
            val existing = remoteFiles[file.name]
            if (existing != null) {
                uploadMedia("$UPLOAD_API/files/$existing?uploadType=media&fields=id", "PATCH", token, file.readText(), mime)
            } else {
                createFileInFolder(folderId, file.name, file.readText(), mime, token)
            }
        }
        // Remove remote files for pages deleted locally.
        for ((name, id) in remoteFiles) {
            if (name !in localNames) delete("$DRIVE_API/files/$id", token)
        }
    }

    private fun pullFiles(docId: String, folderId: String, token: String) {
        val remoteFiles = listFilesInFolder(folderId, token)
        for ((name, id) in remoteFiles) {
            val content = get("$DRIVE_API/files/$id?alt=media", token)
            repo.writeFile(docId, name, content)
        }
        // Remove local files no longer present remotely.
        val remoteNames = remoteFiles.keys
        repo.filesFor(docId).forEach { if (it.name !in remoteNames) it.delete() }
    }

    private fun createFileInFolder(folderId: String, name: String, content: String, mime: String, token: String) {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId))
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(JSON_MEDIA))
            .addPart(content.toRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$UPLOAD_API/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive upload failed: ${resp.code}")
        }
    }

    // ---- Low-level HTTP ----------------------------------------------------

    private fun uploadMedia(url: String, method: String, token: String, content: String, mime: String) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .method(method, content.toRequestBody(mime.toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive upload failed: ${resp.code}")
        }
    }

    private fun get(url: String, token: String): String =
        exec(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())

    private fun post(url: String, token: String, body: okhttp3.RequestBody): String =
        exec(Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build())

    private fun patch(url: String, token: String, body: okhttp3.RequestBody): String =
        exec(Request.Builder().url(url).header("Authorization", "Bearer $token").patch(body).build())

    private fun delete(url: String, token: String) {
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build())
            .execute().use { /* best-effort */ }
    }

    private fun exec(request: Request): String {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Drive request failed: ${resp.code}")
            return text
        }
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".pennotes") || name.endsWith(".json") -> "application/json"
        name.endsWith(".md") -> "text/markdown"
        else -> "text/plain"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        private const val ROOT_NAME = "PenNotes"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
    }
}

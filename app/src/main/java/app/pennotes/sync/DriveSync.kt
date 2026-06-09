package app.pennotes.sync

import android.content.Context
import app.pennotes.model.Notebook
import app.pennotes.storage.NotebookRepository
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
import org.json.JSONObject
import java.net.URLEncoder

/** Outcome of a sync pass, surfaced to the UI as a short status line. */
data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val error: String? = null,
)

/**
 * Two-way sync between the local notebook cache and a "PenNotes" folder in the
 * user's Google Drive. Strokes never leave the device unless the user signs in;
 * the app is fully usable offline. Conflicts resolve last-write-wins using the
 * notebook's own [Notebook.updatedAt], mirrored into Drive appProperties.
 *
 * Sign-in uses the classic Google Sign-In flow with the drive.file scope, which
 * only grants access to files this app creates. See the README for the Google
 * Cloud OAuth client setup required to enable sync.
 */
class DriveSync(private val context: Context, private val repo: NotebookRepository) {

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
        val account = currentAccount()
            ?: return@withContext SyncResult(error = "Not signed in")
        val androidAccount = account.account
            ?: return@withContext SyncResult(error = "No Google account")

        try {
            val token = GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_FILE_SCOPE")
            val folderId = ensureFolder(token)
            val remote = listRemote(folderId, token).associateBy { it.notebookId }

            var uploaded = 0
            var downloaded = 0

            val localSummaries = repo.list()
            val localIds = localSummaries.map { it.id }.toSet()

            for (summary in localSummaries) {
                val local = repo.load(summary.id) ?: continue
                val match = remote[local.id]
                when {
                    match == null -> {
                        createRemote(folderId, local, token); uploaded++
                    }
                    local.updatedAt > match.updatedAt -> {
                        updateRemote(match.fileId, local, token); uploaded++
                    }
                    match.updatedAt > local.updatedAt -> {
                        downloadInto(match.fileId, token)?.let { repo.writeRaw(it); downloaded++ }
                    }
                }
            }

            // Notebooks that only exist remotely get pulled down.
            for ((id, file) in remote) {
                if (id !in localIds) {
                    downloadInto(file.fileId, token)?.let { repo.writeRaw(it); downloaded++ }
                }
            }

            SyncResult(uploaded = uploaded, downloaded = downloaded)
        } catch (e: Exception) {
            SyncResult(error = e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- Drive REST helpers ------------------------------------------------

    private data class RemoteFile(val fileId: String, val notebookId: String, val updatedAt: Long)

    private fun ensureFolder(token: String): String {
        val q = "mimeType='application/vnd.google-apps.folder' and name='$FOLDER_NAME' and trashed=false"
        val url = "$DRIVE_API/files?q=${enc(q)}&fields=files(id)&spaces=drive"
        val body = get(url, token)
        val files = JSONObject(body).optJSONArray("files")
        if (files != null && files.length() > 0) {
            return files.getJSONObject(0).getString("id")
        }
        val meta = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
        val created = post(
            "$DRIVE_API/files?fields=id",
            token,
            meta.toString().toRequestBody(JSON_MEDIA),
        )
        return JSONObject(created).getString("id")
    }

    private fun listRemote(folderId: String, token: String): List<RemoteFile> {
        val q = "'$folderId' in parents and trashed=false"
        val url = "$DRIVE_API/files?q=${enc(q)}&fields=files(id,appProperties)&spaces=drive"
        val body = get(url, token)
        val arr = JSONObject(body).optJSONArray("files") ?: return emptyList()
        val out = mutableListOf<RemoteFile>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val props = f.optJSONObject("appProperties") ?: continue
            val nbId = props.optString("notebookId").ifEmpty { continue }
            val updated = props.optString("updatedAt").toLongOrNull() ?: 0L
            out.add(RemoteFile(f.getString("id"), nbId, updated))
        }
        return out
    }

    private fun metadataJson(notebook: Notebook, includeParents: String?): JSONObject {
        val props = JSONObject()
            .put("notebookId", notebook.id)
            .put("updatedAt", notebook.updatedAt.toString())
        val meta = JSONObject()
            .put("name", "${notebook.title}.pennote")
            .put("appProperties", props)
        if (includeParents != null) {
            meta.put("parents", org.json.JSONArray().put(includeParents))
        }
        return meta
    }

    private fun createRemote(folderId: String, notebook: Notebook, token: String) {
        val meta = metadataJson(notebook, includeParents = folderId)
        multipartUpload("$UPLOAD_API/files?uploadType=multipart&fields=id", "POST", token, meta, notebook)
    }

    private fun updateRemote(fileId: String, notebook: Notebook, token: String) {
        val meta = metadataJson(notebook, includeParents = null)
        multipartUpload("$UPLOAD_API/files/$fileId?uploadType=multipart&fields=id", "PATCH", token, meta, notebook)
    }

    private fun multipartUpload(
        url: String,
        method: String,
        token: String,
        meta: JSONObject,
        notebook: Notebook,
    ) {
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(JSON_MEDIA))
            .addPart(repo.encode(notebook).toRequestBody(JSON_MEDIA))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .method(method, body)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive upload failed: ${resp.code}")
        }
    }

    private fun downloadInto(fileId: String, token: String): Notebook? {
        val body = get("$DRIVE_API/files/$fileId?alt=media", token)
        return runCatching { repo.decode(body) }.getOrNull()
    }

    private fun get(url: String, token: String): String {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Drive request failed: ${resp.code}")
            return text
        }
    }

    private fun post(url: String, token: String, body: okhttp3.RequestBody): String {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Drive request failed: ${resp.code}")
            return text
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_NAME = "PenNotes"
        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
    }
}

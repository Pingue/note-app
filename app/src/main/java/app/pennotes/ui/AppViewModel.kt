package app.pennotes.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pennotes.model.Notebook
import app.pennotes.storage.DocumentRepository
import app.pennotes.storage.DocumentSummary
import app.pennotes.sync.DriveSync
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

/** Drives the whole app: the notebook list, the open notebook, and Drive sync. */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = DocumentRepository(app)
    val driveSync = DriveSync(app, repo)

    var notebooks by mutableStateOf<List<DocumentSummary>>(emptyList())
        private set
    var current by mutableStateOf<Notebook?>(null, neverEqualPolicy())
        private set

    var signedInEmail by mutableStateOf<String?>(null)
        private set
    var syncing by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)

    init {
        refresh()
        refreshAccount()
    }

    fun refresh() = viewModelScope.launch {
        notebooks = repo.list()
    }

    fun refreshAccount() {
        signedInEmail = driveSync.currentAccount()?.email
    }

    fun create(title: String) = viewModelScope.launch {
        val nb = repo.create(title.ifBlank { "Untitled" })
        notebooks = repo.list()
        current = nb
    }

    fun open(id: String) = viewModelScope.launch {
        current = repo.load(id)
    }

    fun close() = viewModelScope.launch {
        current?.let { repo.save(it) }
        current = null
        notebooks = repo.list()
    }

    fun saveCurrent() = viewModelScope.launch {
        current?.let { repo.save(it) }
    }

    fun delete(id: String) = viewModelScope.launch {
        repo.delete(id)
        notebooks = repo.list()
    }

    fun rename(title: String) {
        current?.let { it.title = title.ifBlank { "Untitled" } }
        current = current // neverEqualPolicy forces the UI to pick up the new title
        saveCurrent()
    }

    fun signOut() {
        driveSync.signInClient().signOut().addOnCompleteListener { refreshAccount() }
    }

    /** Parse the Google Sign-In result and surface a precise error if it failed. */
    fun handleSignInResult(data: Intent?) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            signedInEmail = account.email
            statusMessage = "Signed in as ${account.email}"
            sync()
        } catch (e: ApiException) {
            refreshAccount()
            statusMessage = signInErrorMessage(e.statusCode)
        }
    }

    private fun signInErrorMessage(code: Int): String = when (code) {
        // CommonStatusCodes.DEVELOPER_ERROR
        10 -> "Drive sign-in failed (error 10): this APK's signing certificate " +
            "is not registered in a Google Cloud OAuth client. See the README."
        12501 -> "Sign-in cancelled."
        7 -> "Sign-in failed: network error."
        else -> "Drive sign-in failed (error $code)."
    }

    fun sync() {
        if (syncing) return
        viewModelScope.launch {
            syncing = true
            statusMessage = "Syncing…"
            val result = driveSync.sync()
            syncing = false
            statusMessage = when {
                result.error != null -> "Sync failed: ${result.error}"
                else -> "Synced ↑${result.uploaded} ↓${result.downloaded}" +
                    if (result.deleted > 0) " ✕${result.deleted}" else ""
            }
            // A pulled notebook may have replaced the open one on disk; refresh list.
            notebooks = repo.list()
            current?.let { open(it.id) }
        }
    }
}

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
        startAutoSync()
    }

    /** Periodically syncs in the background while signed in (silent unless something changes). */
    private fun startAutoSync() {
        viewModelScope.launch {
            while (isActive) {
                delay(AUTO_SYNC_MS)
                if (driveSync.isSignedIn()) sync(silent = true)
            }
        }
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
        if (driveSync.isSignedIn()) sync(silent = true)
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

    fun sync(silent: Boolean = false) {
        if (syncing) return
        viewModelScope.launch {
            syncing = true
            if (!silent) statusMessage = "Syncing…"
            val result = driveSync.sync()
            syncing = false
            val changed = (result.uploaded + result.downloaded + result.deleted) > 0
            when {
                result.error != null -> if (!silent) statusMessage = "Sync failed: ${result.error}"
                !silent || changed -> statusMessage =
                    "Synced ↑${result.uploaded} ↓${result.downloaded}" +
                        if (result.deleted > 0) " ✕${result.deleted}" else ""
                // Silent pass with no changes: stay quiet.
            }
            // Refresh the list; the open document keeps its in-memory state and is
            // not reloaded here, so active edits are never clobbered mid-session.
            notebooks = repo.list()
        }
    }

    companion object {
        private const val AUTO_SYNC_MS = 5 * 60 * 1000L
    }
}

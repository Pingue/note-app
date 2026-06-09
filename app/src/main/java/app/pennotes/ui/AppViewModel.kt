package app.pennotes.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pennotes.model.Notebook
import app.pennotes.model.Page
import app.pennotes.model.PageOrientation
import app.pennotes.storage.NotebookRepository
import app.pennotes.storage.NotebookSummary
import app.pennotes.sync.DriveSync
import kotlinx.coroutines.launch

/** Drives the whole app: the notebook list, the open notebook, and Drive sync. */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = NotebookRepository(app)
    val driveSync = DriveSync(app, repo)

    var notebooks by mutableStateOf<List<NotebookSummary>>(emptyList())
        private set
    var current by mutableStateOf<Notebook?>(null, neverEqualPolicy())
        private set
    var currentPageIndex by mutableStateOf(0)
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
        currentPageIndex = 0
    }

    fun open(id: String) = viewModelScope.launch {
        current = repo.load(id)
        currentPageIndex = 0
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

    fun goToPage(index: Int) {
        val nb = current ?: return
        currentPageIndex = index.coerceIn(0, nb.pages.size - 1)
    }

    fun addPage(orientation: PageOrientation) {
        val nb = current ?: return
        nb.pages.add(Page(orientation = orientation))
        currentPageIndex = nb.pages.size - 1
        current = current
        saveCurrent()
    }

    fun deleteCurrentPage() {
        val nb = current ?: return
        if (nb.pages.size <= 1) return
        nb.pages.removeAt(currentPageIndex)
        currentPageIndex = currentPageIndex.coerceIn(0, nb.pages.size - 1)
        current = current
        saveCurrent()
    }

    fun setCurrentPageOrientation(orientation: PageOrientation) {
        val nb = current ?: return
        nb.pages.getOrNull(currentPageIndex)?.orientation = orientation
        saveCurrent()
    }

    fun signOut() {
        driveSync.signInClient().signOut().addOnCompleteListener { refreshAccount() }
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
                else -> "Synced ↑${result.uploaded} ↓${result.downloaded}"
            }
            // A pulled notebook may have replaced the open one on disk; refresh list.
            notebooks = repo.list()
            current?.let { open(it.id) }
        }
    }
}

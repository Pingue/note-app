package app.pennotes.storage

import android.content.Context
import app.pennotes.model.Notebook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Lightweight summary used by the notebook list screen. */
data class NotebookSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val updatedAt: Long,
)

/**
 * Owns the on-device notebook store. Each notebook is a single JSON file in the
 * app's private files directory, which doubles as the offline cache for Drive
 * sync. The serialized format is intentionally simple (kotlinx JSON).
 */
class NotebookRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val dir: File = File(context.filesDir, "notebooks").apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, "$id.pennote")

    suspend fun list(): List<NotebookSummary> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.extension == "pennote" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val nb = json.decodeFromString<Notebook>(f.readText())
                    NotebookSummary(nb.id, nb.title, nb.pages.size, nb.updatedAt)
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): Notebook? = withContext(Dispatchers.IO) {
        val f = fileFor(id)
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString<Notebook>(f.readText()) }.getOrNull()
    }

    suspend fun save(notebook: Notebook) = withContext(Dispatchers.IO) {
        notebook.updatedAt = System.currentTimeMillis()
        fileFor(notebook.id).writeText(json.encodeToString(Notebook.serializer(), notebook))
    }

    suspend fun create(title: String): Notebook {
        val nb = Notebook(title = title)
        save(nb)
        return nb
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
        Unit
    }

    /** Used by the sync layer to write a notebook pulled from Drive. */
    suspend fun writeRaw(notebook: Notebook) = withContext(Dispatchers.IO) {
        fileFor(notebook.id).writeText(json.encodeToString(Notebook.serializer(), notebook))
    }

    fun decode(text: String): Notebook = json.decodeFromString(text)
    fun encode(notebook: Notebook): String = json.encodeToString(Notebook.serializer(), notebook)
}

package app.pennotes.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.pennotes.drawing.DrawingView
import app.pennotes.drawing.ToolSettings
import app.pennotes.export.Exporter
import app.pennotes.model.PageOrientation
import app.pennotes.model.ToolType
import kotlinx.coroutines.launch

private val PRESET_COLORS = listOf(
    Color(0xFF000000), Color(0xFF455A64), Color(0xFFD32F2F), Color(0xFFF57C00),
    Color(0xFFFBC02D), Color(0xFF388E3C), Color(0xFF1976D2), Color(0xFF7B1FA2),
)

@Composable
fun EditorScreen(vm: AppViewModel) {
    val notebook = vm.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var settings by remember { mutableStateOf(ToolSettings()) }
    var drawingView by remember { mutableStateOf<DrawingView?>(null) }
    var pageCount by remember(notebook.id) { mutableStateOf(notebook.pages.size) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(vm.statusMessage) {
        vm.statusMessage?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.statusMessage = null
        }
    }

    fun share(mime: String, produce: suspend () -> List<java.io.File>) {
        scope.launch {
            val files = produce()
            if (files.isEmpty()) return@launch
            val uris = ArrayList(files.map { Exporter.uriFor(context, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Export ${notebook.title}"))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Full-screen drawing surface.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                DrawingView(ctx).also { view ->
                    view.onChanged = { vm.saveCurrent() }
                    view.onStructureChanged = { pageCount = view.pageCount() }
                    drawingView = view
                }
            },
            update = { view ->
                if (view.tag != notebook.id) {
                    view.setNotebook(notebook)
                    view.tag = notebook.id
                    pageCount = notebook.pages.size
                }
                view.settings = settings
            },
        )

        // Floating top toolbar overlaid on the canvas.
        EditorTopBar(
            title = notebook.title,
            pageCount = pageCount,
            onBack = { vm.close() },
            onTitleClick = { showRename = true },
            onUndo = { drawingView?.undo() },
            onRedo = { drawingView?.redo() },
            onAddPage = { drawingView?.addPage(it) },
            onExportPdf = { share("application/pdf") { listOf(Exporter.exportPdf(context, notebook)) } },
            onExportJpg = { share("image/jpeg") { Exporter.exportJpgs(context, notebook) } },
            onSync = { vm.sync() },
            syncing = vm.syncing,
            onMakePortrait = { drawingView?.setFocusedPageOrientation(PageOrientation.PORTRAIT) },
            onMakeLandscape = { drawingView?.setFocusedPageOrientation(PageOrientation.LANDSCAPE) },
            onDeletePage = { drawingView?.deleteFocusedPage() },
            onResetZoom = { drawingView?.resetView() },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Floating tool panel overlaid at the bottom.
        ToolPanel(
            settings = settings,
            onSettingsChange = { settings = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp),
        )
    }

    if (showRename) {
        TextPromptDialog(
            title = "Rename notebook",
            initial = notebook.title,
            confirmLabel = "Rename",
            onConfirm = { showRename = false; vm.rename(it) },
            onDismiss = { showRename = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    pageCount: Int,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPage: (PageOrientation) -> Unit,
    onExportPdf: () -> Unit,
    onExportJpg: () -> Unit,
    onSync: () -> Unit,
    syncing: Boolean,
    onMakePortrait: () -> Unit,
    onMakeLandscape: () -> Unit,
    onDeletePage: () -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f).clickable { onTitleClick() }) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$pageCount page${if (pageCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = { addMenu = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add page")
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("Add portrait page") }, onClick = {
                    addMenu = false; onAddPage(PageOrientation.PORTRAIT)
                })
                DropdownMenuItem(text = { Text("Add landscape page") }, onClick = {
                    addMenu = false; onAddPage(PageOrientation.LANDSCAPE)
                })
            }
            IconButton(onClick = onSync, enabled = !syncing) {
                Icon(Icons.Filled.CloudSync, contentDescription = "Sync")
            }
            IconButton(onClick = { overflow = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(text = { Text("Export PDF") }, onClick = { overflow = false; onExportPdf() })
                DropdownMenuItem(text = { Text("Export JPG (per page)") }, onClick = { overflow = false; onExportJpg() })
                DropdownMenuItem(text = { Text("Make page portrait") }, onClick = { overflow = false; onMakePortrait() })
                DropdownMenuItem(text = { Text("Make page landscape") }, onClick = { overflow = false; onMakeLandscape() })
                DropdownMenuItem(text = { Text("Delete current page") }, onClick = { overflow = false; onDeletePage() })
                DropdownMenuItem(text = { Text("Reset zoom") }, onClick = { overflow = false; onResetZoom() })
            }
        }
    }
}

@Composable
private fun ToolPanel(
    settings: ToolSettings,
    onSettingsChange: (ToolSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolChip("Pen", settings.tool == ToolType.PEN) {
                    onSettingsChange(settings.copy(tool = ToolType.PEN))
                }
                ToolChip("Highlighter", settings.tool == ToolType.HIGHLIGHTER) {
                    onSettingsChange(settings.copy(tool = ToolType.HIGHLIGHTER))
                }
                ToolChip("Eraser", settings.tool == ToolType.ERASER) {
                    onSettingsChange(settings.copy(tool = ToolType.ERASER))
                }
            }

            if (settings.tool != ToolType.ERASER) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val selected = if (settings.tool == ToolType.PEN) settings.penColor else settings.highlighterColor
                    PRESET_COLORS.forEach { c ->
                        val argb = c.toArgb()
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(c)
                                .border(
                                    width = if (argb == selected) 3.dp else 1.dp,
                                    color = if (argb == selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    onSettingsChange(
                                        if (settings.tool == ToolType.PEN) settings.copy(penColor = argb)
                                        else settings.copy(highlighterColor = argb)
                                    )
                                },
                        ) {}
                    }
                }
            }

            val (label, value, range) = when (settings.tool) {
                ToolType.PEN -> Triple("Pen size", settings.penSize, 1f..30f)
                ToolType.HIGHLIGHTER -> Triple("Highlighter size", settings.highlighterSize, 8f..60f)
                ToolType.ERASER -> Triple("Eraser size", settings.eraserSize, 10f..120f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
                Slider(
                    value = value,
                    valueRange = range,
                    onValueChange = {
                        onSettingsChange(
                            when (settings.tool) {
                                ToolType.PEN -> settings.copy(penSize = it)
                                ToolType.HIGHLIGHTER -> settings.copy(highlighterSize = it)
                                ToolType.ERASER -> settings.copy(eraserSize = it)
                            }
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp),
    )
}

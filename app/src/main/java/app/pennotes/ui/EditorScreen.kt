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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
    val notebook = vm.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var settings by remember { mutableStateOf(ToolSettings()) }
    var drawingView by remember { mutableStateOf<DrawingView?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(vm.statusMessage) {
        vm.statusMessage?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.statusMessage = null
        }
    }

    val pageIndex = vm.currentPageIndex.coerceIn(0, notebook.pages.size - 1)
    val page = notebook.pages[pageIndex]

    fun share(mime: String, produce: suspend () -> List<java.io.File>) {
        scope.launch {
            val files = produce()
            if (files.isEmpty()) return@launch
            val uris = ArrayList(files.map { Exporter.uriFor(context, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.close() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        notebook.title,
                        modifier = Modifier.clickable { showRename = true },
                    )
                },
                actions = {
                    IconButton(onClick = { drawingView?.undo(); vm.saveCurrent() }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { drawingView?.redo(); vm.saveCurrent() }) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { addMenu = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add page")
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Add portrait page") }, onClick = {
                            addMenu = false; vm.addPage(PageOrientation.PORTRAIT)
                        })
                        DropdownMenuItem(text = { Text("Add landscape page") }, onClick = {
                            addMenu = false; vm.addPage(PageOrientation.LANDSCAPE)
                        })
                    }
                    IconButton(onClick = { exportMenu = true }) {
                        Icon(Icons.Filled.Share, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(text = { Text("Export PDF") }, onClick = {
                            exportMenu = false
                            share("application/pdf") { listOf(Exporter.exportPdf(context, notebook)) }
                        })
                        DropdownMenuItem(text = { Text("Export JPG (per page)") }, onClick = {
                            exportMenu = false
                            share("image/jpeg") { Exporter.exportJpgs(context, notebook) }
                        })
                    }
                    IconButton(onClick = { vm.sync() }, enabled = !vm.syncing) {
                        Icon(Icons.Filled.CloudSync, contentDescription = "Sync")
                    }
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(text = { Text("Rename notebook") }, onClick = {
                            overflow = false; showRename = true
                        })
                        DropdownMenuItem(text = { Text("Make page portrait") }, onClick = {
                            overflow = false
                            vm.setCurrentPageOrientation(PageOrientation.PORTRAIT)
                            drawingView?.resetView()
                        })
                        DropdownMenuItem(text = { Text("Make page landscape") }, onClick = {
                            overflow = false
                            vm.setCurrentPageOrientation(PageOrientation.LANDSCAPE)
                            drawingView?.resetView()
                        })
                        DropdownMenuItem(text = { Text("Reset zoom") }, onClick = {
                            overflow = false; drawingView?.resetView()
                        })
                        DropdownMenuItem(text = { Text("Delete this page") }, onClick = {
                            overflow = false; vm.deleteCurrentPage()
                        })
                    }
                },
            )
        },
        bottomBar = {
            ToolPanel(
                settings = settings,
                onSettingsChange = { settings = it },
                pageIndex = pageIndex,
                pageCount = notebook.pages.size,
                onPrev = { vm.goToPage(pageIndex - 1) },
                onNext = { vm.goToPage(pageIndex + 1) },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            factory = { ctx ->
                DrawingView(ctx).also { view ->
                    view.onChanged = { vm.saveCurrent() }
                    drawingView = view
                }
            },
            update = { view ->
                if (view.tag != page.id) {
                    view.setPage(page)
                    view.tag = page.id
                }
                view.settings = settings
            },
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

@Composable
private fun ToolPanel(
    settings: ToolSettings,
    onSettingsChange: (ToolSettings) -> Unit,
    pageIndex: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPrev, enabled = pageIndex > 0) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
                }
                Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
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

package app.pennotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pennotes.ui.AppViewModel
import app.pennotes.ui.EditorScreen
import app.pennotes.ui.NotebookListScreen
import app.pennotes.ui.theme.PenNotesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PenNotesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Whatever the result, re-read the cached account and sync if signed in.
        vm.refreshAccount()
        if (vm.signedInEmail != null) vm.sync()
    }

    val launchSignIn = { signInLauncher.launch(vm.driveSync.signInClient().signInIntent) }

    if (vm.current == null) {
        NotebookListScreen(
            vm = vm,
            onSignIn = launchSignIn,
        )
    } else {
        EditorScreen(vm = vm)
    }
}

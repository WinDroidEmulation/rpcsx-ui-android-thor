package net.rpcsx.ui.tools

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.Game
import net.rpcsx.R
import net.rpcsx.tools.TrimAnalyzer
import net.rpcsx.tools.TrimCandidate
import net.rpcsx.tools.TrimRisk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    game: Game?,
    navigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val candidates = remember { mutableStateListOf<TrimCandidate>() }
    val selected = remember { mutableStateListOf<TrimCandidate>() }
    var includeRisky by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var pendingApply by remember { mutableStateOf(false) }

    val externalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scope.launch {
                isScanning = true
                val result = withContext(Dispatchers.IO) { TrimAnalyzer.analyzeExternal(context, uri) }
                candidates.clear()
                candidates.addAll(result)
                selected.clear()
                selected.addAll(result.filter { it.risk == TrimRisk.Safe })
                isScanning = false
            }
        }
    }

    val visibleCandidates = candidates.filter { includeRisky || it.risk == TrimRisk.Safe }
    val selectedVisible = selected.filter { it in visibleCandidates }
    val totalSelectedSize = selectedVisible.sumOf { it.size }
    val hasRiskySelection = selectedVisible.any { it.risk == TrimRisk.Risky }

    fun scanInstalled() {
        val path = game?.info?.path ?: return
        scope.launch {
            isScanning = true
            val result = withContext(Dispatchers.IO) { TrimAnalyzer.analyzeInstalled(path) }
            candidates.clear()
            candidates.addAll(result)
            selected.clear()
            selected.addAll(result.filter { it.risk == TrimRisk.Safe })
            isScanning = false
        }
    }

    if (pendingApply) {
        AlertDialog(
            onDismissRequest = { pendingApply = false },
            title = { Text(if (hasRiskySelection) "Apply risky trim?" else "Apply trim?") },
            text = {
                Text(
                    if (hasRiskySelection) {
                        "Risky items may remove language, audio, or video assets. Installed-game files are moved to a backup; external selections are deleted."
                    } else {
                        "Safe items are normally removable emulator clutter. Installed-game files are moved to a backup before removal. Continue?"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingApply = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            TrimAnalyzer.apply(context, selectedVisible)
                        }
                        candidates.removeAll(selectedVisible.toSet())
                        selected.removeAll(selectedVisible.toSet())
                        Toast.makeText(
                            context,
                            "Backed up ${result.backedUp}, deleted ${result.deleted}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingApply = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        game?.info?.name?.value ?: "Trim / Optimize",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scanInstalled() },
                    enabled = game != null && !isScanning
                ) {
                    Icon(painter = painterResource(id = R.drawable.memory), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Installed")
                }
                Button(onClick = { externalPicker.launch(null) }, enabled = !isScanning) {
                    Icon(painter = painterResource(id = R.drawable.ic_folder), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("External")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show risky candidates", modifier = Modifier.weight(1f))
                Switch(checked = includeRisky, onCheckedChange = { includeRisky = it })
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${visibleCandidates.size} candidates")
                    Text("Selected: ${selectedVisible.size} (${TrimAnalyzer.formatSize(totalSelectedSize)})")
                    Text("Installed-game files are moved to trim_backups; external selections are deleted through Android storage.")
                    if (isScanning) {
                        Text("Scanning...")
                    }
                }
            }

            Button(
                onClick = { pendingApply = true },
                enabled = selectedVisible.isNotEmpty() && !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_delete), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Apply Selected")
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleCandidates, key = { it.displayPath }) { candidate ->
                    TrimCandidateCard(
                        candidate = candidate,
                        checked = candidate in selected,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (candidate !in selected) selected += candidate
                            } else {
                                selected -= candidate
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrimCandidateCard(
    candidate: TrimCandidate,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(candidate.displayPath, style = MaterialTheme.typography.titleSmall)
                Text(candidate.reason, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${candidate.risk.name}  ${TrimAnalyzer.formatSize(candidate.size)}",
                    color = if (candidate.risk == TrimRisk.Risky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

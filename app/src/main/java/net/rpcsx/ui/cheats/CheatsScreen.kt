package net.rpcsx.ui.cheats

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import net.rpcsx.EmulatorState
import net.rpcsx.Game
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.cheats.ArtemisConverter
import net.rpcsx.cheats.ArtemisInstallResult
import net.rpcsx.cheats.CheatEntry
import net.rpcsx.cheats.CheatRepository
import net.rpcsx.cheats.CheatSelectionRepository
import net.rpcsx.cheats.PatchHashRepository
import net.rpcsx.cheats.PatchHashStatus
import net.rpcsx.utils.GameIdentity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatsScreen(
    game: Game?,
    navigateBack: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<CheatEntry?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var selectedError by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }
    var selectionNonce by remember { mutableStateOf(0) }
    var isInstalling by remember { mutableStateOf(false) }
    var installResult by remember { mutableStateOf<ArtemisInstallResult?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var patchStatus by remember(game?.info?.path) {
        mutableStateOf(game?.let { PatchHashRepository.cachedStatus(context, it) })
    }
    val scope = rememberCoroutineScope()

    val gameKey = game?.let { GameIdentity.primaryTitleId(it) ?: it.info.path } ?: "global"
    val isGameRunning = game != null &&
        RPCSX.activeGame.value == game.info.path &&
        RPCSX.state.value != EmulatorState.Stopped &&
        RPCSX.state.value != EmulatorState.Stopping

    LaunchedEffect(refreshNonce, game?.info?.path) {
        CheatRepository.load(context, forceRefresh = refreshNonce > 0)
        patchStatus = game?.let { PatchHashRepository.learnFromLogs(context, it) }
    }

    LaunchedEffect(selectedEntry) {
        val entry = selectedEntry
        selectedText = null
        selectedError = null
        if (entry != null) {
            try {
                selectedText = CheatRepository.getCheatText(context, entry)
            } catch (e: Exception) {
                selectedError = e.message
            }
        }
    }

    val matchedEntries = game?.let { CheatRepository.matches(it) }.orEmpty()
    val baseEntries = if (query.isBlank() && game != null) {
        matchedEntries
    } else {
        CheatRepository.search(query)
    }
    val selectionVersion = selectionNonce
    val installEntries = if (game != null && selectionVersion >= 0) {
        CheatSelectionRepository.enabledEntries(context, gameKey, matchedEntries)
    } else {
        emptyList()
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
                        game?.info?.name?.value ?: "Cheat Database",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshNonce++ }) {
                        Icon(painter = painterResource(id = R.drawable.ic_refresh), contentDescription = null)
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search title, ID, or version") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (isGameRunning) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "This game is running. Cheat changes are staged for next boot until live patch toggles are wired into the native core.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (CheatRepository.isLoading.value) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            CheatRepository.lastError.value?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (game != null) {
                CheatInstallCard(
                    selectedCount = installEntries.size,
                    matchedCount = matchedEntries.size,
                    patchStatus = patchStatus,
                    isInstalling = isInstalling,
                    result = installResult,
                    error = installError,
                    onApplySelected = {
                        scope.launch {
                            isInstalling = true
                            installResult = null
                            installError = null
                            try {
                                installResult = ArtemisConverter.installEntries(context, game, installEntries)
                                patchStatus = PatchHashRepository.learnFromLogs(context, game)
                            } catch (e: Exception) {
                                installError = e.message ?: "Failed to install Artemis patches"
                            } finally {
                                isInstalling = false
                            }
                        }
                    },
                    onInstallAll = {
                        scope.launch {
                            isInstalling = true
                            installResult = null
                            installError = null
                            try {
                                installResult = ArtemisConverter.installEntries(context, game, matchedEntries)
                                patchStatus = PatchHashRepository.learnFromLogs(context, game)
                            } catch (e: Exception) {
                                installError = e.message ?: "Failed to install Artemis patches"
                            } finally {
                                isInstalling = false
                            }
                        }
                    },
                    onClear = {
                        scope.launch {
                            isInstalling = true
                            installResult = null
                            installError = null
                            try {
                                installResult = ArtemisConverter.installEntries(context, game, emptyList())
                                patchStatus = PatchHashRepository.learnFromLogs(context, game)
                            } catch (e: Exception) {
                                installError = e.message ?: "Failed to clear Artemis patches"
                            } finally {
                                isInstalling = false
                            }
                        }
                    }
                )
            }

            Text("${baseEntries.size} entries", style = MaterialTheme.typography.labelLarge)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(baseEntries, key = { it.fileName }) { entry ->
                    CheatEntryCard(
                        entry = entry,
                        enabled = CheatSelectionRepository.isEnabled(context, gameKey, entry),
                        onEnabledChange = {
                            CheatSelectionRepository.setEnabled(context, gameKey, entry, it)
                            selectionNonce++
                            installResult = null
                            installError = null
                        },
                        onOpen = { selectedEntry = entry }
                    )
                }

                item {
                    selectedEntry?.let { entry ->
                        CheatPreview(
                            entry = entry,
                            text = selectedText,
                            error = selectedError,
                            onCopy = {
                                val cheatText = selectedText ?: return@CheatPreview
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(entry.fileName, cheatText))
                                Toast.makeText(context, "Copied cheat text", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatInstallCard(
    selectedCount: Int,
    matchedCount: Int,
    patchStatus: PatchHashStatus?,
    isInstalling: Boolean,
    result: ArtemisInstallResult?,
    error: String?,
    onApplySelected: () -> Unit,
    onInstallAll: () -> Unit,
    onClear: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Artemis patches", style = MaterialTheme.typography.titleMedium)
            patchStatus?.let {
                Text("Patch status: ${PatchHashRepository.statusText(it)}", style = MaterialTheme.typography.bodySmall)
                if (it.ppuHash == null && it.titleId != null) {
                    Text("Boot once to learn the PPU hash required by RPCSX patches.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                if (selectedCount == 0) {
                    "No selected entries. Install all fixed cheats or pick entries below."
                } else {
                    "$selectedCount selected entries will be converted to next-boot RPCSX patches."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = onApplySelected,
                enabled = selectedCount > 0 && !isInstalling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_build), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isInstalling) "Installing..." else "Apply Selected")
            }
            Button(
                onClick = onInstallAll,
                enabled = matchedCount > 0 && !isInstalling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_star), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Install All Safe")
            }
            TextButton(
                onClick = onClear,
                enabled = !isInstalling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Generated Patches")
            }
            if (isInstalling) {
                CircularProgressIndicator()
            }
            result?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall)
                if (it.patchFilePath != null && it.configFilePath != null) {
                    Text(
                        "Patch: ${it.patchFilePath}\nConfig: ${it.configFilePath}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (it.backupPaths.isNotEmpty()) {
                    Text("Existing patch files were backed up before adding RPCSX Easy sections.", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CheatEntryCard(
    entry: CheatEntry,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    entry.titleIds.ifEmpty { listOf("No title ID") }.joinToString(),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    listOf(entry.version, entry.size).filter { it.isNotBlank() }.joinToString("  "),
                    style = MaterialTheme.typography.bodySmall
                )
                if (entry.convertibleCount != null && entry.riskyCount != null) {
                    Text(
                        "${entry.convertibleCount} safe, ${entry.riskyCount} risky",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (entry.format == CheatRepository.FORMAT_RPCS3_PATCH) {
                        "RPCS3-ready patch"
                    } else {
                        "Artemis NCL"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onOpen) {
                Text("View")
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun CheatPreview(
    entry: CheatEntry,
    text: String?,
    error: String?,
    onCopy: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.fileName, style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (text == null && error == null) {
                CircularProgressIndicator()
            } else if (text != null) {
                val summary = if (entry.format == CheatRepository.FORMAT_RPCS3_PATCH) {
                    (entry.convertibleCount ?: 1) to (entry.riskyCount ?: 0)
                } else {
                    ArtemisConverter.summarize(text)
                }
                Text(
                    if (entry.format == CheatRepository.FORMAT_RPCS3_PATCH) {
                        "${summary.first} RPCS3-ready patches, ${summary.second} risky/unsupported."
                    } else {
                        "${summary.first} fixed-write cheats convertible, ${summary.second} risky/unsupported skipped."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onCopy) {
                    Icon(painter = painterResource(id = R.drawable.ic_description), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (entry.format == CheatRepository.FORMAT_RPCS3_PATCH) {
                            "Copy Patch"
                        } else {
                            "Copy NCL"
                        }
                    )
                }
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Source: ${CheatRepository.sourceUrl(entry)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

package net.rpcsx.ui.cheats

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
import androidx.compose.runtime.derivedStateOf
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
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatsScreen(
    game: Game?,
    navigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var showUnavailable by remember(game?.info?.path) { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<CheatEntry?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var selectedError by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }
    var selectionNonce by remember { mutableStateOf(0) }
    var isSaving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<ArtemisInstallResult?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var patchStatus by remember(game?.info?.path) {
        mutableStateOf(game?.let { PatchHashRepository.cachedStatus(context, it) })
    }
    var expandedGameEntries by remember(game?.info?.path) { mutableStateOf<List<CheatEntry>>(emptyList()) }
    var expandedGlobalEntries by remember { mutableStateOf<List<CheatEntry>>(emptyList()) }
    var isExpandingGlobal by remember { mutableStateOf(false) }
    var globalExpandError by remember { mutableStateOf<String?>(null) }

    val gameKey = game?.let { GameIdentity.primaryTitleId(it) ?: it.info.path } ?: "global"
    val isGameRunning = game != null &&
        RPCSX.activeGame.value == game.info.path &&
        RPCSX.state.value != EmulatorState.Stopped &&
        RPCSX.state.value != EmulatorState.Stopping

    LaunchedEffect(refreshNonce, game?.info?.path) {
        CheatRepository.load(context, forceRefresh = refreshNonce > 0)
        patchStatus = game?.let { PatchHashRepository.learnFromLogs(context, it) }
    }

    LaunchedEffect(
        game?.info?.path,
        refreshNonce,
        CheatRepository.entries.size,
        CheatRepository.isLoading.value
    ) {
        if (game != null || CheatRepository.entries.isEmpty() || CheatRepository.isLoading.value) {
            return@LaunchedEffect
        }

        isExpandingGlobal = true
        globalExpandError = null
        expandedGlobalEntries = runCatching { CheatRepository.expandAllEntries(context) }
            .getOrElse {
                globalExpandError = it.message ?: "Failed to prepare the cheat list"
                CheatRepository.entries.toList()
            }
        isExpandingGlobal = false
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
    LaunchedEffect(game?.info?.path, matchedEntries.joinToString("|") { it.fileName }) {
        expandedGameEntries = if (game == null || matchedEntries.isEmpty()) {
            emptyList()
        } else {
            runCatching { CheatRepository.expandEntries(context, matchedEntries) }
                .getOrElse { matchedEntries }
        }
    }

    val gameEntries = if (game != null) expandedGameEntries.ifEmpty { matchedEntries } else emptyList()
    val globalEntries = expandedGlobalEntries.ifEmpty { CheatRepository.entries.toList() }
    val sourceEntries = if (game != null) gameEntries else globalEntries
    val visibleEntries by remember(sourceEntries, showUnavailable) {
        derivedStateOf { sourceEntries.filter { showUnavailable || isReadyCheat(it) } }
    }
    val baseEntries by remember(visibleEntries, query) {
        derivedStateOf { filterEntries(visibleEntries, query) }
    }
    LaunchedEffect(game?.info?.path, query, baseEntries.joinToString("|") { entryKey(it) }) {
        val selected = selectedEntry
        if (selected != null && baseEntries.none { entryKey(it) == entryKey(selected) }) {
            selectedEntry = null
        }
    }

    val selectionVersion = selectionNonce
    val enabledEntries = if (game != null && selectionVersion >= 0) {
        CheatSelectionRepository.enabledEntries(context, gameKey, gameEntries)
    } else {
        emptyList()
    }
    val hiddenUnavailableCount by remember(sourceEntries) {
        derivedStateOf { sourceEntries.count { !isReadyCheat(it) } }
    }

    fun saveCheatToggles() {
        val targetGame = game ?: return
        if (isSaving) {
            return
        }

        val selected = CheatSelectionRepository.enabledEntries(context, gameKey, gameEntries)
        isSaving = true
        saveResult = null
        saveError = null
        scope.launch {
            try {
                saveResult = ArtemisConverter.installEntries(context, targetGame, selected)
                patchStatus = PatchHashRepository.learnFromLogs(context, targetGame)
            } catch (e: Exception) {
                saveError = e.message ?: "Failed to save cheats"
            } finally {
                isSaving = false
            }
        }
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
                        if (game == null) "Browse All Cheats" else "Cheats",
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
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (game != null) {
                item {
                    Text(
                        game.info.name.value ?: "This game",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                item {
                    CheatStatusCard(
                        enabledCount = enabledEntries.size,
                        needsFirstBoot = needsFirstBoot(sourceEntries, patchStatus),
                        isGameRunning = isGameRunning,
                        isSaving = isSaving,
                        result = saveResult,
                        error = saveError
                    )
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "This is a read-only browser. Open a game to turn its cheats on or off.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(if (game == null) "Search games or cheats" else "Search cheats") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            if (hiddenUnavailableCount > 0) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Show cheats that are not available yet",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Switch(checked = showUnavailable, onCheckedChange = { showUnavailable = it })
                    }
                }
            }

            if (CheatRepository.isLoading.value || isExpandingGlobal) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isExpandingGlobal) "Preparing cheats" else "Loading cheats",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            CheatRepository.lastError.value?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }

            globalExpandError?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }

            item {
                Text("${baseEntries.size} cheats", style = MaterialTheme.typography.labelLarge)
            }

            items(baseEntries, key = { entryKey(it) }) { entry ->
                val canToggle = game != null && canToggleCheat(entry, patchStatus)
                val isEnabled = game != null && CheatSelectionRepository.isEnabled(context, gameKey, entry)
                CheatEntryCard(
                    entry = entry,
                    enabled = isEnabled,
                    showToggle = game != null,
                    toggleEnabled = canToggle && !isSaving,
                    status = cheatStatusText(entry, isEnabled, canToggle, game != null),
                    onEnabledChange = { checked ->
                        if (game != null && canToggle) {
                            CheatSelectionRepository.setEnabled(context, gameKey, entry, checked)
                            selectionNonce++
                            saveCheatToggles()
                        }
                    },
                    onOpen = { selectedEntry = entry }
                )

                if (selectedEntry?.let { entryKey(it) } == entryKey(entry)) {
                    Spacer(Modifier.height(8.dp))
                    CheatPreview(
                        entry = entry,
                        text = selectedText,
                        error = selectedError,
                        canToggle = canToggle,
                        isGameScreen = game != null
                    )
                }
            }
        }
    }
}

@Composable
private fun CheatStatusCard(
    enabledCount: Int,
    needsFirstBoot: Boolean,
    isGameRunning: Boolean,
    isSaving: Boolean,
    result: ArtemisInstallResult?,
    error: String?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Toggle cheats on or off", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    isSaving -> "Saving..."
                    needsFirstBoot -> "One-time setup: start this game once, close it, then come back to turn cheats on."
                    isGameRunning -> "Saved changes take effect next time you start this game."
                    enabledCount > 0 -> "$enabledCount on. Changes are saved for the next time you start this game."
                    else -> "Toggles save automatically."
                },
                style = MaterialTheme.typography.bodySmall
            )
            result?.let {
                Text(friendlySaveMessage(it, enabledCount), style = MaterialTheme.typography.bodySmall)
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
    showToggle: Boolean,
    toggleEnabled: Boolean,
    status: String,
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
                Text(entry.cheatName ?: entry.title, style = MaterialTheme.typography.titleMedium)
                if (entry.cheatName != null) {
                    Text(entry.title, style = MaterialTheme.typography.bodySmall)
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (entry.titleIds.isNotEmpty()) {
                    Text(entry.titleIds.joinToString(), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onOpen) {
                Text("Details")
            }
            if (showToggle) {
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = toggleEnabled
                )
            }
        }
    }
}

@Composable
private fun CheatPreview(
    entry: CheatEntry,
    text: String?,
    error: String?,
    canToggle: Boolean,
    isGameScreen: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.cheatName ?: entry.fileName, style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (text == null && error == null) {
                CircularProgressIndicator()
            } else if (text != null) {
                if (entry.format == CheatRepository.FORMAT_RPCS3_PATCH) {
                    Text(
                        if (isGameScreen) {
                            "This cheat is ready to toggle for this game."
                        } else {
                            "This cheat is ready when opened from its game page."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    val selectedCheats = ArtemisConverter.selectedCheats(text, entry)
                    val patchOps = selectedCheats.flatMap { it.writes }
                    val unavailableReasons = selectedCheats.flatMap { it.unsupportedReasons }.distinct()

                    Text(
                        if (canToggle) {
                            "This cheat can be turned on for the next game start."
                        } else if (isReadyCheat(entry)) {
                            "Start this game once, close it, then this cheat can be turned on."
                        } else {
                            "This cheat is in the library, but RPCSX Easy cannot use it yet."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (patchOps.isNotEmpty()) {
                        Text("Technical changes", style = MaterialTheme.typography.labelLarge)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            patchOps.take(40).forEach { write ->
                                Text(
                                    "${write.patchType} 0x${write.address} = 0x${write.value}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (patchOps.size > 40) {
                                Text("${patchOps.size - 40} more changes", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (unavailableReasons.isNotEmpty()) {
                        Text("Why it is not available yet", style = MaterialTheme.typography.labelLarge)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            unavailableReasons.forEach { reason ->
                                Text(reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text(
                    "Source: ${CheatRepository.sourceUrl(entry)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun filterEntries(entries: List<CheatEntry>, query: String): List<CheatEntry> {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) {
        return entries
    }

    return entries.filter { entry ->
        entry.cheatName.orEmpty().lowercase().contains(needle) ||
            entry.title.lowercase().contains(needle) ||
            entry.version.lowercase().contains(needle) ||
            entry.fileName.lowercase().contains(needle) ||
            entry.titleIds.any { it.lowercase().contains(needle) }
    }
}

private fun isReadyCheat(entry: CheatEntry): Boolean =
    entry.format == CheatRepository.FORMAT_RPCS3_PATCH || (entry.convertibleCount ?: 0) > 0

private fun canToggleCheat(entry: CheatEntry, patchStatus: PatchHashStatus?): Boolean {
    if (!isReadyCheat(entry)) {
        return false
    }

    if (entry.format == CheatRepository.FORMAT_RPCS3_PATCH) {
        return true
    }

    return patchStatus?.ppuHash != null
}

private fun needsFirstBoot(entries: List<CheatEntry>, patchStatus: PatchHashStatus?): Boolean =
    patchStatus?.ppuHash == null &&
        entries.any { it.format != CheatRepository.FORMAT_RPCS3_PATCH && isReadyCheat(it) }

private fun cheatStatusText(
    entry: CheatEntry,
    enabled: Boolean,
    canToggle: Boolean,
    isGameScreen: Boolean
): String = when {
    !isReadyCheat(entry) -> "Not available yet"
    !isGameScreen -> "Open this game to turn it on"
    enabled -> "On for next start"
    canToggle -> "Off"
    else -> "Start game once to unlock"
}

private fun friendlySaveMessage(result: ArtemisInstallResult, enabledCount: Int): String {
    if (result.missingHash) {
        return "Start this game once, close it, then come back to turn cheats on."
    }

    return if (enabledCount == 0 || result.installedCheats == 0) {
        "All cheats are off."
    } else {
        "Saved. Cheats take effect next time you start this game."
    }
}

private fun entryKey(entry: CheatEntry): String =
    "${entry.fileName}:${entry.cheatIndex ?: "all"}:${entry.cheatName.orEmpty()}"

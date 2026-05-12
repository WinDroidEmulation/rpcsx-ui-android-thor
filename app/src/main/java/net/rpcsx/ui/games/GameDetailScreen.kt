package net.rpcsx.ui.games

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.Game
import net.rpcsx.R
import net.rpcsx.cheats.CheatEntry
import net.rpcsx.cheats.CheatRepository
import net.rpcsx.cheats.CheatSelectionRepository
import net.rpcsx.cheats.PatchHashRepository
import net.rpcsx.cheats.PatchHashStatus
import net.rpcsx.config.GameSettingsDatabase
import net.rpcsx.performance.GameCacheRepository
import net.rpcsx.utils.GameIdentity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    game: Game,
    navigateBack: () -> Unit,
    navigateToCheats: () -> Unit,
    navigateToTrim: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launchGame = rememberGameLauncher(game)
    val titleIds = GameIdentity.titleIdsForGame(game)
    var hashStatus by remember(game.info.path) { mutableStateOf(PatchHashRepository.cachedStatus(context, game)) }
    var expandedCheats by remember(game.info.path) { mutableStateOf<List<CheatEntry>>(emptyList()) }
    var settingsStatus by remember(game.info.path) { mutableStateOf<GameSettingsDatabase.Status?>(null) }
    var settingsCacheMessage by remember(game.info.path) { mutableStateOf<String?>(null) }
    var settingsCacheUpdating by remember(game.info.path) { mutableStateOf(false) }
    var cacheStatus by remember(game.info.path) { mutableStateOf<GameCacheRepository.CacheStatus?>(null) }
    var cacheMessage by remember(game.info.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(game.info.path) {
        CheatRepository.load(context)
        hashStatus = PatchHashRepository.learnFromLogs(context, game)
        settingsStatus = withContext(Dispatchers.IO) {
            GameSettingsDatabase.ensureDatabaseExported(context)
            GameSettingsDatabase.statusForGame(context, game)
        }
        cacheStatus = withContext(Dispatchers.IO) {
            GameCacheRepository.statusForGame(game)
        }
    }

    val matchedCheats = CheatRepository.matches(game)
    LaunchedEffect(game.info.path, matchedCheats.joinToString("|") { it.fileName }) {
        expandedCheats = if (matchedCheats.isEmpty()) {
            emptyList()
        } else {
            runCatching { CheatRepository.expandEntries(context, matchedCheats) }
                .getOrElse { matchedCheats }
        }
    }

    val cheatRows = expandedCheats.ifEmpty { matchedCheats }
    val readyCheats = cheatRows.filter { isReadyCheat(it) }
    val unavailableCount = cheatRows.size - readyCheats.size
    val enabledCount = CheatSelectionRepository.enabledCount(
        context,
        GameIdentity.primaryTitleId(game) ?: game.info.path,
        cheatRows
    )

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        game.info.name.value ?: "Game",
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
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    game.info.name.value ?: "Unknown title",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            item {
                Text(
                    if (titleIds.isEmpty()) "No title ID detected" else titleIds.joinToString(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Path", style = MaterialTheme.typography.labelLarge)
                        Text(game.info.path, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = launchGame) {
                        Icon(painter = painterResource(id = R.drawable.ic_play), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play")
                    }
                    Button(onClick = navigateToCheats, enabled = cheatRows.isNotEmpty()) {
                        Icon(painter = painterResource(id = R.drawable.ic_star), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cheats")
                    }
                }
            }

            item {
                RecommendedSettingsCard(
                    status = settingsStatus,
                    cacheMessage = settingsCacheMessage,
                    cacheUpdating = settingsCacheUpdating,
                    onToggle = { enabled ->
                        scope.launch {
                            settingsStatus = withContext(Dispatchers.IO) {
                                GameSettingsDatabase.setRecommendedSettingsEnabled(
                                    context,
                                    game,
                                    enabled
                                )
                            }
                        }
                    },
                    onUpdateCache = {
                        scope.launch {
                            settingsCacheUpdating = true
                            val shouldApply = settingsStatus?.enabled == true
                            val result = withContext(Dispatchers.IO) {
                                val refresh = GameSettingsDatabase.refreshLocalCache(context)
                                val status = if (shouldApply) {
                                    GameSettingsDatabase.applyRecommendedConfig(context, game)
                                } else {
                                    GameSettingsDatabase.statusForGame(context, game)
                                }
                                refresh to status
                            }
                            settingsCacheMessage = result.first.message
                            settingsStatus = result.second
                            settingsCacheUpdating = false
                        }
                    }
                )
            }

            item {
                CacheSummaryCard(
                    status = cacheStatus,
                    message = cacheMessage,
                    onRefresh = {
                        scope.launch {
                            cacheStatus = withContext(Dispatchers.IO) {
                                GameCacheRepository.statusForGame(game)
                            }
                            cacheMessage = null
                        }
                    },
                    onPrepareCache = {
                        cacheMessage = "Preparing compiled cache."
                        GameCacheRepository.prepareGameCache(context, game) { status ->
                            cacheStatus = status
                            cacheMessage = if (status.isWarm) {
                                "Compiled cache is ready."
                            } else {
                                "Compiled cache was not built yet."
                            }
                        }
                    },
                    onClearCache = {
                        scope.launch {
                            cacheStatus = withContext(Dispatchers.IO) {
                                GameCacheRepository.clearGameCache(game)
                            }
                            cacheMessage = "Compiled cache cleared."
                        }
                    }
                )
            }

            item {
                Button(onClick = navigateToTrim, modifier = Modifier.fillMaxWidth()) {
                    Icon(painter = painterResource(id = R.drawable.tune), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Trim / Optimize")
                }
            }

            item {
                CheatSummaryCard(
                    readyCheats = readyCheats,
                    unavailableCount = unavailableCount,
                    enabledCount = enabledCount,
                    hashStatus = hashStatus,
                    onOpenCheats = navigateToCheats
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Most cheats are saved before launch and take effect the next time you start the game.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CacheSummaryCard(
    status: GameCacheRepository.CacheStatus?,
    message: String?,
    onRefresh: () -> Unit,
    onPrepareCache: () -> Unit,
    onClearCache: () -> Unit
) {
    val title = when {
        status == null -> "Checking Cache"
        status.titleId == null -> "Compiled Cache"
        status.isWarm -> "Compiled Cache Ready"
        status.exists -> "Compiled Cache Started"
        else -> "Compiled Cache Not Built"
    }
    val detail = when {
        status == null -> "Checking PPU/SPU/shader cache state."
        status.titleId == null -> "No title ID detected yet."
        status.isWarm -> "${GameCacheRepository.formatBytes(status.bytes)} cached for ${status.titleId}."
        status.exists -> "Cache folder exists for ${status.titleId}, but no PPU/SPU/shader entries were found yet."
        else -> "Start this game once to build its PPU/SPU/shader cache during boot and play."
    }
    val canPrepare = status?.prepareSupported == true && status.titleId != null
    val canClear = status?.exists == true

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = "Refresh cache status"
                    )
                }
            }

            if (status != null && status.titleId != null) {
                Text(
                    "PPU: ${status.ppuEntries}; SPU: ${status.spuEntries}; shaders: ${status.shaderEntries}; total entries: ${status.totalEntries}.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (status != null && !status.prepareSupported) {
                Text(
                    "Background prepare needs a newer RPCSX core. Shader cache warms only while the game renders new effects.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!message.isNullOrBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrepareCache, enabled = canPrepare, modifier = Modifier.weight(1f)) {
                    Icon(painter = painterResource(id = R.drawable.ic_play), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Prepare")
                }
                Button(onClick = onClearCache, enabled = canClear, modifier = Modifier.weight(1f)) {
                    Icon(painter = painterResource(id = R.drawable.ic_delete), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun RecommendedSettingsCard(
    status: GameSettingsDatabase.Status?,
    cacheMessage: String?,
    cacheUpdating: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdateCache: () -> Unit
) {
    val switchEnabled = status?.hasProfile == true && !status.customConfigPresent
    val checked = status?.enabled == true
    val message = when {
        status == null -> "Checking recommended settings."
        status.titleId == null -> "No title ID detected yet."
        !status.hasProfile -> "No recommended settings for ${status.titleId} yet."
        status.customConfigPresent -> "Custom settings already exist; leaving them alone."
        status.enabled && status.managedConfigStale -> "On; newer cached settings will be saved next start."
        status.enabled && status.applied -> "On for next start."
        status.enabled -> "On; will be saved when you start the game."
        else -> "Off for this game."
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Recommended Settings", style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(
                    onClick = onUpdateCache,
                    enabled = !cacheUpdating
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = "Update settings cache"
                    )
                }
                Switch(
                    checked = checked,
                    enabled = switchEnabled,
                    onCheckedChange = onToggle
                )
            }

            if (status?.databaseProfileCount != null) {
                Text(
                    "Settings cache: ${status.databaseProfileCount} profiles (${status.databaseSource ?: "ready"}).",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (status?.databaseTimestamp != null && status.hasProfile) {
                Text("Profile ${status.titleId}", style = MaterialTheme.typography.bodySmall)
            }

            if (cacheUpdating) {
                Text("Updating settings cache.", style = MaterialTheme.typography.bodySmall)
            } else if (!cacheMessage.isNullOrBlank()) {
                Text(cacheMessage, style = MaterialTheme.typography.bodySmall)
            }

            if (status?.error != null) {
                Text(status.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CheatSummaryCard(
    readyCheats: List<CheatEntry>,
    unavailableCount: Int,
    enabledCount: Int,
    hashStatus: PatchHashStatus,
    onOpenCheats: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cheats", style = MaterialTheme.typography.titleMedium)
            Text("${readyCheats.size} available", style = MaterialTheme.typography.bodySmall)
            if (unavailableCount > 0) {
                Text("$unavailableCount more are not available yet", style = MaterialTheme.typography.bodySmall)
            }
            if (needsFirstBoot(readyCheats, hashStatus)) {
                Text(
                    "One-time setup: start this game once, close it, then come back to turn cheats on.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (enabledCount > 0) {
                Text("$enabledCount on for next start", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Open Cheats to turn them on or off.", style = MaterialTheme.typography.bodySmall)
            }
            readyCheats.take(6).forEach { entry ->
                Text(entry.cheatName ?: entry.title, style = MaterialTheme.typography.bodySmall)
            }
            if (readyCheats.size > 6) {
                Text("${readyCheats.size - 6} more", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onOpenCheats,
                enabled = readyCheats.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_star), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Cheats")
            }
        }
    }
}

private fun isReadyCheat(entry: CheatEntry): Boolean =
    entry.format == CheatRepository.FORMAT_RPCS3_PATCH || (entry.convertibleCount ?: 0) > 0

private fun needsFirstBoot(entries: List<CheatEntry>, hashStatus: PatchHashStatus): Boolean =
    hashStatus.ppuHash == null &&
        entries.any { it.format != CheatRepository.FORMAT_RPCS3_PATCH && isReadyCheat(it) }

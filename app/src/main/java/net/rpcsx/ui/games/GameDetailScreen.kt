package net.rpcsx.ui.games

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.rpcsx.Game
import net.rpcsx.R
import net.rpcsx.cheats.ArtemisConverter
import net.rpcsx.cheats.ArtemisInstallResult
import net.rpcsx.cheats.CheatEntry
import net.rpcsx.cheats.CheatRepository
import net.rpcsx.cheats.CheatSelectionRepository
import net.rpcsx.cheats.PatchHashRepository
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
    val launchGame = rememberGameLauncher(game)
    val scope = rememberCoroutineScope()
    val titleIds = GameIdentity.titleIdsForGame(game)
    var hashStatus by remember(game.info.path) { mutableStateOf(PatchHashRepository.cachedStatus(context, game)) }
    var isInstalling by remember(game.info.path) { mutableStateOf(false) }
    var installResult by remember(game.info.path) { mutableStateOf<ArtemisInstallResult?>(null) }
    var installError by remember(game.info.path) { mutableStateOf<String?>(null) }
    var expandedCheats by remember(game.info.path) { mutableStateOf<List<CheatEntry>>(emptyList()) }

    LaunchedEffect(game.info.path) {
        CheatRepository.load(context)
        hashStatus = PatchHashRepository.learnFromLogs(context, game)
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
    val fixedCheatCount = cheatRows.sumOf { it.convertibleCount ?: 0 }
    val riskyCheatCount = cheatRows.sumOf { it.riskyCount ?: 0 }
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
                    Button(onClick = navigateToCheats) {
                        Icon(painter = painterResource(id = R.drawable.ic_star), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cheats")
                    }
                }
            }

            item {
                Button(onClick = navigateToTrim, modifier = Modifier.fillMaxWidth()) {
                    Icon(painter = painterResource(id = R.drawable.tune), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Trim / Optimize")
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cheat database", style = MaterialTheme.typography.titleMedium)
                        Text("${cheatRows.size} individual cheats")
                        Text("$fixedCheatCount static cheats, $riskyCheatCount risky/runtime")
                        Text("Patch status: ${PatchHashRepository.statusText(hashStatus)}")
                        hashStatus.ppuHash?.let { Text("PPU hash: $it", style = MaterialTheme.typography.bodySmall) }
                        if (hashStatus.ppuHash == null && hashStatus.titleId != null) {
                            Text("Boot this game once, close it, then RPCSX Easy can bind patches to the learned PPU hash.")
                        }
                        if (cheatRows.isNotEmpty()) {
                            Text("Available cheats", style = MaterialTheme.typography.labelLarge)
                            cheatRows.take(10).forEach { entry ->
                                Text(
                                    "${if ((entry.convertibleCount ?: 0) > 0) "Safe" else "Risky"} - ${entry.cheatName ?: entry.title}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (cheatRows.size > 10) {
                                Text("${cheatRows.size - 10} more in the full Cheats list", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (enabledCount > 0) {
                            Text("$enabledCount selected for patch install")
                        }
                        if (CheatRepository.lastError.value != null) {
                            Text(CheatRepository.lastError.value ?: "", color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = navigateToCheats,
                            enabled = cheatRows.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(painter = painterResource(id = R.drawable.ic_star), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Cheat List")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    isInstalling = true
                                    installResult = null
                                    installError = null
                                    try {
                                        installResult = ArtemisConverter.installEntries(context, game, cheatRows)
                                        hashStatus = PatchHashRepository.learnFromLogs(context, game)
                                        Toast.makeText(
                                            context,
                                            installResult?.message ?: "Installed Artemis patches",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (e: Exception) {
                                        installError = e.message ?: "Failed to install Artemis patches"
                                    } finally {
                                        isInstalling = false
                                    }
                                }
                            },
                            enabled = cheatRows.isNotEmpty() && !isInstalling,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(painter = painterResource(id = R.drawable.ic_build), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isInstalling) "Installing..." else "Install All Safe Cheats")
                        }
                        if (isInstalling) {
                            CircularProgressIndicator()
                        }
                        installResult?.let {
                            Text(it.message, style = MaterialTheme.typography.bodySmall)
                        }
                        installError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Live cheat toggles need a native RPCSX bridge. Artemis static cheats can be installed as next-boot patches.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

package net.rpcsx

import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.InvalidParameterException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

enum class GameFlag {
    Locked,
    Trial
}

@Serializable
data class GameInfo @Keep constructor(
    val path: String,
    var name: String? = null,
    var iconPath: String? = null,
    var gameFlags: Int = 0,
    var titleId: String? = null,
    var sourceUri: String? = null
) {
    @Keep
    constructor(
        path: String,
        name: String?,
        iconPath: String?,
        gameFlags: Int
    ) : this(path, name, iconPath, gameFlags, null, null)
}

data class GameInfoStore(
    val path: String,
    val name: MutableState<String?> = mutableStateOf(null),
    val iconPath: MutableState<String?> = mutableStateOf(null),
    val gameFlags: MutableIntState = mutableIntStateOf(0),
    val titleId: MutableState<String?> = mutableStateOf(null),
    val sourceUri: MutableState<String?> = mutableStateOf(null)
)

enum class GameProgressType {
    Install,
    Compile,
    Remove,
}

data class GameProgress(val id: Long, val type: GameProgressType)

data class Game(
    val info: GameInfoStore,
    val progressList: SnapshotStateList<GameProgress> = mutableStateListOf()
) {
    fun addProgress(progress: GameProgress) {
        if (findProgress(progress.type) != null) {
            throw InvalidParameterException()
        }

        progressList += progress
    }

    fun findProgress(type: GameProgressType) =
        progressList.filter { elem -> elem.type == type }.ifEmpty { null }

    fun findProgress(types: Array<GameProgressType>) =
        progressList.filter { elem -> types.contains(elem.type) }.ifEmpty { null }

    fun removeProgress(type: GameProgressType) =
        progressList.removeIf { progress -> progress.type == type }

    fun hasFlag(flag: GameFlag) = (info.gameFlags.intValue and (1 shl flag.ordinal)) != 0
}

private fun toStore(info: GameInfo) =
    GameInfoStore(
        info.path,
        mutableStateOf(info.name),
        mutableStateOf(info.iconPath),
        mutableIntStateOf(info.gameFlags),
        mutableStateOf(info.titleId),
        mutableStateOf(info.sourceUri)
    )

private fun toInfo(store: GameInfoStore) =
    GameInfo(
        path = store.path,
        name = store.name.value,
        iconPath = store.iconPath.value,
        gameFlags = store.gameFlags.intValue,
        titleId = store.titleId.value,
        sourceUri = store.sourceUri.value
    )

class GameRepository {
    private val games = mutableStateListOf<Game>()

    companion object {
        private val instance = GameRepository()

        private val mainHandler = Handler(Looper.getMainLooper())
        private val refreshLock = Any()
        private var needsRefresh = false
        private var refreshRunning = false
        val isRefreshing = mutableStateOf(false)

        fun save() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { save() }
                return
            }

            val games = synchronized(instance) {
                instance.games.map { game -> toInfo(game.info) }.filter { info -> info.path != "$" }
            }
            thread {
                saveSnapshot(games)
            }
        }

        private fun saveSnapshot(games: List<GameInfo>) {
            try {
                File(RPCSX.rootDirectory + "games.json").writeText(Json.encodeToString(games))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        suspend fun load() {
            val loadedGames = withContext(Dispatchers.IO) {
                try {
                    Json.decodeFromString<Array<GameInfo>>(
                        File(RPCSX.rootDirectory + "games.json").readText()
                    ).map { info -> Game(toStore(info)) }
                } catch (_: NotFoundException) {
                    emptyList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }

            withContext(Dispatchers.Main) {
                synchronized(instance) {
                    instance.games.clear()
                    instance.games += loadedGames
                }
            }
        }

        fun queueRefresh() {
            synchronized(refreshLock) {
                needsRefresh = true
                if (refreshRunning) {
                    return
                }
                refreshRunning = true
            }

            runOnMain { isRefreshing.value = true }
            thread {
                while (true) {
                    synchronized(refreshLock) {
                        needsRefresh = false
                    }
                    refresh()
                    Thread.sleep(300)

                    synchronized(refreshLock) {
                        if (!needsRefresh) {
                            refreshRunning = false
                            break
                        }
                    }
                }
                runOnMain { isRefreshing.value = false }
            }
        }

        private fun refresh() {
            runOnMainBlocking { clear() }
            RPCSX.instance.collectGameInfo(
                RPCSX.rootDirectory + "/config/dev_hdd0/game", -1
            )
            RPCSX.instance.collectGameInfo(RPCSX.rootDirectory + "/config/games", -1)
        }
        
        @Keep
        @JvmStatic
        fun add(gameInfos: Array<GameInfo>, progressId: Long) {
            runOnMainBlocking {
                synchronized(instance) {
                    if (progressId >= 0) {
                        val progressEntry =
                            instance.games.filter { game -> game.info.path == "$" }.find { game ->
                                val progress = game.findProgress(GameProgressType.Install)
                                    ?.find { progress -> progress.id == progressId }
                                progress != null
                            }

                        if (progressEntry != null) {
                            instance.games.remove(progressEntry)
                        }
                    }

                    gameInfos.forEach { info ->
                        val existsGame = instance.games.find { x -> x.info.path == info.path }
                        if (existsGame == null) {
                            val newGame = Game(toStore(info))
                            if (progressId >= 0) {
                                newGame.addProgress(GameProgress(progressId, GameProgressType.Install))
                            }
                            instance.games.add(0, newGame)
                        } else {
                            existsGame.info.name.value = info.name ?: existsGame.info.name.value
                            existsGame.info.iconPath.value =
                                info.iconPath ?: existsGame.info.iconPath.value
                            existsGame.info.gameFlags.intValue = info.gameFlags
                            existsGame.info.titleId.value =
                                info.titleId ?: existsGame.info.titleId.value
                            existsGame.info.sourceUri.value =
                                info.sourceUri ?: existsGame.info.sourceUri.value
                            if (progressId >= 0) {
                                existsGame.addProgress(
                                    GameProgress(
                                        progressId,
                                        GameProgressType.Install
                                    )
                                )
                            }
                        }
                    }
                    save()
                }
            }
        }

        fun addPreview(gameInfos: Array<GameInfo>) {
            runOnMain {
                instance.games += gameInfos.map { info -> Game(toStore(info)) }
            }
        }

        fun onBoot(game: Game) {
            runOnMain {
                synchronized(instance) {
                    if (instance.games.firstOrNull() != game) {
                        instance.games.remove(game)
                        instance.games.add(0, game)
                        save()
                    }
                }
            }
        }

        fun createGameInstallEntry(progressId: Long) {
            runOnMain {
                synchronized(instance) {
                    val game = Game(GameInfoStore("$"))
                    game.addProgress(GameProgress(progressId, GameProgressType.Install))
                    instance.games.add(0, game)
                }
            }
        }

        fun clearProgress(progressId: Long) {
            runOnMain {
                synchronized(instance) {
                    instance.games.forEach { game -> game.progressList.removeIf { progress -> progress.id == progressId } }
                    instance.games.removeIf { game -> game.info.path == "$" && game.progressList.isEmpty() }
                }
            }
        }

        fun remove(game: Game) {
            runOnMain {
                synchronized(instance) {
                    instance.games -= game
                    save()
                }
            }
        }

        fun find(path: String): Game? {
            synchronized(instance) {
                return instance.games.find { game -> game.info.path == path }
            }
        }

        fun list() = instance.games

        fun clear() {
            instance.games.clear()
        }

        private fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                mainHandler.post(block)
            }
        }

        private fun runOnMainBlocking(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
                return
            }

            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    block()
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
        }
    }
}

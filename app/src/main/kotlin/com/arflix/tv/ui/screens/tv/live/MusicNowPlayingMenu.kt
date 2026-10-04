package com.arflix.tv.ui.screens.tv.live

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arflix.tv.music.MaLineup
import com.arflix.tv.ui.components.ContextAction
import com.arflix.tv.ui.components.ContextMenu
import com.arflix.tv.ui.screens.tv.TvViewModel
import org.json.JSONObject

private const val PlaySongId = "np_song"
private const val PlayPauseId = "np_playpause"
private const val NextId = "np_next"
private const val PreviousId = "np_prev"
private const val VolUpId = "np_volup"
private const val VolDownId = "np_voldown"
private const val MoveId = "np_move"

/**
 * Player controls for a guide "Now Playing · <room>" row: play/pause, next/previous, volume, and
 * moving the music to another room -- so the guide does what the Music screen did (Joe,
 * 2026-10-04: "press and have player control so [I don't] have to go to music section").
 *
 * Its own file on purpose: LiveTvScreen is already big enough that Android 11's verifier on the
 * Shield rejected it once (2bbb18e).
 */
@Composable
fun MusicNowPlayingMenu(
    viewModel: TvViewModel,
    queueId: String,
    lineup: MaLineup?,
    // A song cell on the row: offers "Play this song" first.
    cellProgram: com.arflix.tv.data.model.IptvProgram?,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val speakers by viewModel.musicSpeakers.collectAsStateWithLifecycle()
    val zones by viewModel.musicZones.collectAsStateWithLifecycle()
    LaunchedEffect(queueId) { viewModel.refreshMusicZones() }
    // Optimistic: flips on the press, MA's state catches up on the next lineup refresh.
    var pausedOverride by remember(queueId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(lineup?.paused) { pausedOverride = null }
    var moving by remember(queueId) { mutableStateOf(false) }

    val zone = speakers.firstOrNull { it.id == queueId }
    val zoneName = lineup?.zoneName ?: zone?.name ?: "Music"
    val paused = pausedOverride ?: lineup?.paused ?: (zone?.isPlaying == false)
    val current = lineup?.tracks?.firstOrNull()
    // The cell's song, if it's a later one in the queue (the current song is already playing).
    val cellSong = remember(cellProgram, lineup) {
        cellProgram?.let { p ->
            lineup?.tracks?.drop(1)?.firstOrNull { t -> listOfNotNull(t.name, t.artist).joinToString(" · ") == p.title }
        }
    }

    fun send(command: String, args: JSONObject = JSONObject().put("queue_id", queueId)) =
        viewModel.musicControl(command, args) { ok -> if (!ok) onMessage("$zoneName didn't respond") }

    fun volume(up: Boolean) {
        val leader = zone?.isGroupLeader == true
        val cmd = when {
            leader && up -> "players/cmd/group_volume_up"
            leader -> "players/cmd/group_volume_down"
            up -> "players/cmd/volume_up"
            else -> "players/cmd/volume_down"
        }
        send(cmd, JSONObject().put("player_id", queueId))
    }

    key(moving) {
        if (moving) {
            ContextMenu(
                isVisible = true,
                title = "Move to",
                subtitle = "The queue keeps its place; $zoneName stops",
                actions = zones.filter { it.id != queueId && !it.isTvAudio }
                    .map { ContextAction(it.id, it.name, Icons.Default.Speaker) },
                onAction = { action ->
                    val target = zones.firstOrNull { it.id == action.id } ?: return@ContextMenu
                    viewModel.transferMusicQueue(queueId, target.id) { ok ->
                        onMessage(if (ok) "Moved to ${target.name}" else "Couldn't move to ${target.name}")
                    }
                    onClose()
                },
                onDismiss = { moving = false },
            )
        } else {
            val volumeLabel = zone?.effectiveVolume?.let { " · volume $it" }.orEmpty()
            ContextMenu(
                isVisible = true,
                title = current?.let { listOfNotNull(it.name, it.artist).joinToString(" · ") } ?: "Now Playing",
                subtitle = (if (paused) "Paused on " else "Playing on ") + zoneName + volumeLabel,
                actions = buildList {
                    cellSong?.let { add(ContextAction(PlaySongId, "Play “${it.name}” now", Icons.Default.MusicNote)) }
                    add(ContextAction(PlayPauseId, if (paused) "Play" else "Pause", if (paused) Icons.Default.PlayArrow else Icons.Default.Pause))
                    add(ContextAction(NextId, "Next song", Icons.Default.SkipNext))
                    add(ContextAction(PreviousId, "Previous song", Icons.Default.SkipPrevious))
                    add(ContextAction(VolUpId, "Volume up", Icons.Default.VolumeUp))
                    add(ContextAction(VolDownId, "Volume down", Icons.Default.VolumeDown))
                    add(ContextAction(MoveId, "Move to another room…", Icons.Default.Speaker))
                },
                // Controls keep the menu open, so you can skip or turn it down a few times.
                onAction = { action ->
                    when (action.id) {
                        PlaySongId -> {
                            cellSong?.queueItemId?.let { id ->
                                send("player_queues/play_index", JSONObject().put("queue_id", queueId).put("index", id))
                            }
                            onClose()
                        }
                        PlayPauseId -> {
                            pausedOverride = !paused
                            send("player_queues/play_pause")
                        }
                        NextId -> send("player_queues/next")
                        PreviousId -> send("player_queues/previous")
                        VolUpId -> volume(up = true)
                        VolDownId -> volume(up = false)
                        MoveId -> moving = true
                    }
                },
                onDismiss = onClose,
            )
        }
    }
}

package com.arflix.tv.data.repository

import com.arflix.tv.data.model.MediaType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receiving side of Remote Mode: `WebAppServer`'s remote-command handlers push commands here
 * instead of reaching into a specific screen directly, since the receiving device may be
 * sitting on any screen (or none — backgrounded). `MainActivity` collects `incoming` once at
 * the top level and acts on each command from wherever the app currently is.
 */
sealed class RemoteCommand {
    // Local channel id, already resolved against THIS device's own IPTV snapshot by the
    // /api/remote/tune-channel handler — never the sender's raw tvg-id directly.
    data class TuneChannel(val localChannelId: String) : RemoteCommand()
    data class PlayTitle(
        val mediaType: MediaType,
        val tmdbId: Int,
        val season: Int?,
        val episode: Int?,
    ) : RemoteCommand()
    data class DPad(val key: DPadKey) : RemoteCommand()
    data class TypeText(val text: String) : RemoteCommand()
}

enum class DPadKey { UP, DOWN, LEFT, RIGHT, CENTER, BACK }

@Singleton
class RemoteCommandBus @Inject constructor() {
    private val _incoming = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 4)
    val incoming: SharedFlow<RemoteCommand> = _incoming

    fun emit(command: RemoteCommand) {
        _incoming.tryEmit(command)
    }
}

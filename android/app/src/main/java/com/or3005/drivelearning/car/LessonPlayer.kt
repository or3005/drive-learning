package com.or3005.drivelearning.car

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.or3005.drivelearning.LearningController
import com.or3005.drivelearning.LearningController.Mode
import com.or3005.drivelearning.engine.Command
import com.or3005.drivelearning.engine.ListenContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Presents the lesson to Android Auto as a media player. Play and pause control the lesson,
 * the steering wheel's next and previous buttons skip forward and go back, and the "track"
 * title shows what is happening now. Next and previous (steering wheel or screen) move about 15
 * seconds forward or back. The playlist is always three items so both buttons always show.
 */
@UnstableApi
class LessonPlayer(
    private val learning: LearningController,
    looper: Looper,
) : SimpleBasePlayer(looper) {

    private val scope = MainScope()

    @OptIn(FlowPreview::class)
    private val updates = scope.launch { learning.state.debounce(250).collect { invalidateState() } }

    override fun getState(): State {
        val s = learning.state.value
        val playing = s.mode == Mode.SPEAKING || s.mode == Mode.LISTENING || s.mode == Mode.WAITING
        val title = when (s.awaiting) {
            ListenContext.ANSWER -> s.prompt.ifBlank { "שאלה" }
            ListenContext.CONFIDENCE -> "בטוח, או לא בטוח?"
            ListenContext.YES_NO -> "לשמוע שוב את החלק שמסביר את זה?"
            ListenContext.EXPLAIN -> "הסבר במילים שלך"
            else -> when (s.mode) {
                Mode.IDLE -> "מוכן להתחיל"
                Mode.FINISHED -> "סיימנו להיום"
                Mode.PAUSED -> "בהשהיה · ${s.label}".trimEnd(' ', '·')
                Mode.LISTENING -> "מקשיב…"
                else -> s.label.ifBlank { "Drive Learning" }
            }
        }
        val subtitle = s.unitTitle.ifBlank { "הושלמו ${s.completedUnits} מתוך ${s.totalUnits} יחידות" }

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
            )
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(
                listOf(
                    item("back", "⏪ 15 שניות אחורה", subtitle),
                    item("now", title, subtitle),
                    item("skip", "⏩ 15 שניות קדימה", subtitle),
                ),
            )
            .setCurrentMediaItemIndex(1)
            .build()
    }

    private fun item(uid: String, title: String, subtitle: String): MediaItemData =
        MediaItemData.Builder(uid)
            .setMediaItem(MediaItem.Builder().setMediaId(uid).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtist(subtitle)
                    .setSubtitle(subtitle)
                    .build(),
            )
            .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) learning.resume() else learning.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        learning.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            // Steering wheel and screen next/previous: about 15 seconds forward or back.
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> learning.onCommand(Command.FastForward)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> learning.onCommand(Command.Rewind)
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    /** Choosing the start item in Android Auto's browse list. The lesson starts on play. */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}

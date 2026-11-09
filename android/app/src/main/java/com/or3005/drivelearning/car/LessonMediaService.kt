package com.or3005.drivelearning.car

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.or3005.drivelearning.DriveLearningApp
import com.or3005.drivelearning.LearningController
import com.or3005.drivelearning.LearningController.Mode
import com.or3005.drivelearning.R
import com.or3005.drivelearning.engine.Command
import com.or3005.drivelearning.engine.ListenContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Android Auto talks to this service like to any music app. The browse list has one item that
 * starts or continues the lesson. On the now-playing screen, the custom buttons change with the
 * lesson: answer numbers during a question, sure/unsure after an answer, and so on.
 */
@UnstableApi
class LessonMediaService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = MainScope()
    private val learning: LearningController by lazy { (application as DriveLearningApp).controller }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        val player = LessonPlayer(learning, Looper.getMainLooper())
        val librarySession = MediaLibrarySession.Builder(this, player, Callback()).build()
        session = librarySession
        scope.launch {
            learning.state
                .map { buttonsFor(it) }
                .distinctUntilChanged()
                .debounce(400) // only show buttons that stay put long enough to tap
                .collect { buttons -> librarySession.setCustomLayout(buttons.map { it.toCommandButton() }) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---- buttons on the now-playing screen -----------------------------------------------------

    private data class Button(val action: String, val label: String, val icon: Int) {
        fun toCommandButton(): CommandButton = CommandButton.Builder()
            .setDisplayName(label)
            .setIconResId(icon)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .build()
    }

    private fun buttonsFor(s: LearningController.UiState): List<Button> = when (s.awaiting) {
        ListenContext.ANSWER -> (1..s.options.size.coerceIn(1, 4)).map { n ->
            Button("answer$n", "תשובה $n", ANSWER_ICONS[n - 1])
        } + Button(REPEAT_QUESTION, "חזור על השאלה", R.drawable.ic_replay)
        ListenContext.CONFIDENCE -> listOf(
            Button(SURE, "בטוח", R.drawable.ic_check),
            Button(UNSURE, "לא בטוח", R.drawable.ic_question),
            Button(VOICE, "ענה בקול", R.drawable.ic_mic),
        )
        ListenContext.YES_NO -> listOf(
            Button(YES, "כן, תשמיע שוב", R.drawable.ic_check),
            Button(NO, "לא, תמשיך", R.drawable.ic_cross),
            Button(VOICE, "ענה בקול", R.drawable.ic_mic),
        )
        ListenContext.EXPLAIN -> listOf(
            Button(VOICE, "דבר עכשיו", R.drawable.ic_mic),
            Button(HINT, "רמז", R.drawable.ic_bulb),
        )
        else -> if (s.mode == Mode.IDLE || s.mode == Mode.FINISHED) {
            emptyList()
        } else {
            listOf(
                Button(SIMPLER, "תסביר אחרת", R.drawable.ic_bubble),
                Button(SKIP, "דלג לחלק הבא", R.drawable.ic_skip),
                Button(VOICE, "פקודה קולית", R.drawable.ic_mic),
                Button(REPLAY, "תחזור על זה", R.drawable.ic_replay),
                Button(PRONUNCIATION, "הגייה לא נכונה", R.drawable.ic_pronounce),
            )
        }
    }

    private fun handle(action: String) {
        when (action) {
            SURE -> learning.onCommand(Command.Sure(true))
            UNSURE -> learning.onCommand(Command.Sure(false))
            YES -> learning.onCommand(Command.YesNo(true))
            NO -> learning.onCommand(Command.YesNo(false))
            REPLAY -> learning.onCommand(Command.Replay)
            SIMPLER -> learning.onCommand(Command.Simpler)
            HINT -> learning.onCommand(Command.Hint)
            VOICE -> learning.listenNow()
            SKIP -> learning.onCommand(Command.Forward)
            REPEAT_QUESTION -> learning.onCommand(Command.RepeatQuestion)
            PRONUNCIATION -> learning.reportPronunciation()
            else -> action.removePrefix("answer").toIntOrNull()?.let { learning.onCommand(Command.Answer(it)) }
        }
    }

    // ---- session callback: browse tree and custom commands ------------------------------------

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands = base.availableSessionCommands.buildUpon()
            ALL_ACTIONS.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.accept(commands.build(), base.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            handle(customCommand.customAction)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(courseItems()), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    if (mediaId == ROOT_ID) rootItem() else courseItems().firstOrNull { it.mediaId == mediaId } ?: courseItems().first(),
                    null,
                ),
            )

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Drive Learning")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    /** One playable item per course, the current course first. Choosing one switches to it. */
    private fun courseItems(): List<MediaItem> {
        val s = learning.state.value
        val courses = s.courses.ifEmpty {
            listOf(LearningController.CourseChoice(s.courseId, s.courseTitle, s.completedUnits, s.totalUnits))
        }
        return courses.sortedByDescending { it.id == s.courseId }.map { c ->
            val current = c.id == s.courseId
            MediaItem.Builder()
                .setMediaId(COURSE_PREFIX + c.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(if (current) "▶ להמשיך: ${c.title}" else "▶ ${c.title}")
                        .setSubtitle("הושלמו ${c.completed} מתוך ${c.total} יחידות")
                        .setArtist(c.title)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
        }
    }

    companion object {
        private const val ROOT_ID = "root"
        const val COURSE_PREFIX = "course:"
        private const val SURE = "sure"
        private const val UNSURE = "unsure"
        private const val YES = "yes"
        private const val NO = "no"
        private const val REPLAY = "replay"
        private const val SIMPLER = "simpler"
        private const val HINT = "hint"
        private const val VOICE = "voice"
        private const val SKIP = "skip"
        private const val REPEAT_QUESTION = "repeat_question"
        private const val PRONUNCIATION = "pronunciation"
        private val ALL_ACTIONS = listOf(SURE, UNSURE, YES, NO, REPLAY, SIMPLER, HINT, VOICE, SKIP, REPEAT_QUESTION, PRONUNCIATION) +
            (1..4).map { "answer$it" }
        private val ANSWER_ICONS = listOf(R.drawable.ic_n1, R.drawable.ic_n2, R.drawable.ic_n3, R.drawable.ic_n4)
    }
}

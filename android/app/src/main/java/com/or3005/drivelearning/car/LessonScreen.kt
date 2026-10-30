package com.or3005.drivelearning.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.or3005.drivelearning.DriveLearningApp
import com.or3005.drivelearning.LearningController.Mode
import com.or3005.drivelearning.LearningController.UiState
import com.or3005.drivelearning.engine.Command
import com.or3005.drivelearning.engine.ListenContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The car screen. Always one ListTemplate with the same title and exactly six rows, so every
 * update counts as a refresh and not as a new step (Android Auto limits steps per task).
 * Driving is voice-first; the rows are big tap targets for when voice isn't convenient.
 */
class LessonScreen(carContext: CarContext) : Screen(carContext) {

    private val controller = (carContext.applicationContext as DriveLearningApp).controller
    private val mic = CarMic(carContext)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                controller.carMic = mic
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (controller.carMic === mic) controller.carMic = null
                mic.close()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state
                    .map { listOf(it.mode, it.awaiting, it.label, it.prompt, it.options, it.unitTitle, it.message) }
                    .distinctUntilChanged()
                    .collect { invalidate() }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val s = controller.state.value
        val rows = when (s.awaiting) {
            ListenContext.ANSWER -> questionRows(s)
            ListenContext.CONFIDENCE -> listOf(
                info("כמה אתה בטוח?", s.unitTitle),
                tap("👍 בטוח") { controller.onCommand(Command.Sure(true)) },
                tap("🤔 לא בטוח") { controller.onCommand(Command.Sure(false)) },
                tap("↺ חזור על השאלה") { controller.onCommand(Command.RepeatQuestion) },
                tap("⏸ עצור") { controller.onCommand(Command.Pause) },
                tap("🎤 ענה בקול") { controller.listenNow() },
            )
            ListenContext.YES_NO -> listOf(
                info("לשמוע שוב את החלק שמסביר את זה?", s.unitTitle),
                tap("כן, תשמיע שוב") { controller.onCommand(Command.YesNo(true)) },
                tap("לא, תמשיך") { controller.onCommand(Command.YesNo(false)) },
                tap("💡 רמז") { controller.onCommand(Command.Hint) },
                tap("⏸ עצור") { controller.onCommand(Command.Pause) },
                tap("🎤 ענה בקול") { controller.listenNow() },
            )
            ListenContext.EXPLAIN -> listOf(
                info(s.prompt.ifBlank { "הסבר במילים שלך" }, "הסבר במילים שלך"),
                tap("🎤 דבר עכשיו") { controller.listenNow() },
                tap("💡 רמז") { controller.onCommand(Command.Hint) },
                tap("⏭ דלג") { controller.onCommand(Command.Forward) },
                tap("↺ חזור על השאלה") { controller.onCommand(Command.RepeatQuestion) },
                tap("⏸ עצור") { controller.onCommand(Command.Pause) },
            )
            else -> playerRows(s)
        }
        val list = ItemList.Builder().apply { rows.take(6).forEach { addItem(it) } }.build()
        return ListTemplate.Builder()
            .setTitle("Drive Learning")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list)
            .build()
    }

    private fun questionRows(s: UiState): List<Row> {
        val rows = mutableListOf(info(s.prompt.ifBlank { "שאלה" }, s.unitTitle))
        s.options.take(4).forEachIndexed { i, option ->
            rows += tap("${i + 1}. $option") { controller.onCommand(Command.Answer(i + 1)) }
        }
        if (rows.size < 5) rows += tap("💡 רמז") { controller.onCommand(Command.Hint) }
        if (rows.size < 6) rows += tap("🎤 ענה בקול") { controller.listenNow() }
        return rows
    }

    private fun playerRows(s: UiState): List<Row> {
        val status = when (s.mode) {
            Mode.IDLE -> "מוכן להתחיל"
            Mode.FINISHED -> "סיימנו להיום"
            Mode.PAUSED -> "בהשהיה"
            Mode.LISTENING -> "מקשיב…"
            else -> s.label.ifBlank { "משמיע" }
        }
        val playPause = when (s.mode) {
            Mode.SPEAKING, Mode.LISTENING -> tap("⏸ עצור") { controller.onCommand(Command.Pause) }
            Mode.PAUSED, Mode.WAITING -> tap("▶ המשך") { controller.resume() }
            else -> tap("▶ התחל נסיעה") { controller.start() }
        }
        return listOf(
            info(status, s.unitTitle.ifBlank { "${s.completedUnits} מתוך ${s.totalUnits} יחידות הושלמו" }),
            playPause,
            tap("↺ תחזור על זה") { controller.onCommand(Command.Replay) },
            tap("💡 תסביר אחרת") { controller.onCommand(Command.Simpler) },
            tap("⏭ דלג") { controller.onCommand(Command.Forward) },
            tap("🎤 פקודה קולית") { controller.listenNow() },
        )
    }

    private fun info(title: String, text: String): Row {
        val builder = Row.Builder().setTitle(title.ifBlank { " " })
        if (text.isNotBlank()) builder.addText(text)
        return builder.build()
    }

    private fun tap(title: String, onClick: () -> Unit): Row =
        Row.Builder().setTitle(title).setOnClickListener { onClick() }.build()
}

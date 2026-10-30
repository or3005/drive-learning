package com.or3005.drivelearning.ui

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.or3005.drivelearning.DriveLearningApp
import com.or3005.drivelearning.LearningController
import com.or3005.drivelearning.LearningController.Mode
import com.or3005.drivelearning.engine.Command
import com.or3005.drivelearning.engine.ListenContext
import kotlinx.coroutines.launch

/**
 * Phone screen. Works on its own (listen over Bluetooth in the car, or at home), and doubles as
 * the setup screen for permissions, content updates and voice settings.
 */
class MainActivity : ComponentActivity() {

    private val controller: LearningController by lazy { (application as DriveLearningApp).controller }

    private lateinit var status: TextView
    private lateinit var unitTitle: TextView
    private lateinit var label: TextView
    private lateinit var prompt: TextView
    private lateinit var heard: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var confidenceBox: LinearLayout
    private lateinit var yesNoBox: LinearLayout
    private lateinit var playButton: Button
    private lateinit var progressText: TextView
    private lateinit var setupText: TextView
    private lateinit var englishSwitch: Switch
    private val optionButtons = mutableListOf<Button>()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        askPermissions()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { render(it) }
            }
        }
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
    }

    private fun render(s: LearningController.UiState) {
        status.text = when (s.mode) {
            Mode.IDLE -> "מוכן להתחיל"
            Mode.SPEAKING -> "משמיע"
            Mode.LISTENING -> "🎤 מקשיב…"
            Mode.WAITING -> "מחכה לתשובה"
            Mode.PAUSED -> "בהשהיה"
            Mode.FINISHED -> "סיימנו להיום"
        }
        unitTitle.text = s.unitTitle
        label.text = s.label
        prompt.text = s.prompt
        prompt.visibility = if (s.prompt.isBlank()) View.GONE else View.VISIBLE
        heard.text = if (s.heard.isBlank()) "" else "שמעתי: ${s.heard}"

        val answering = s.awaiting == ListenContext.ANSWER
        optionsBox.visibility = if (answering) View.VISIBLE else View.GONE
        optionButtons.forEachIndexed { i, b ->
            val text = s.options.getOrNull(i)
            b.visibility = if (answering && text != null) View.VISIBLE else View.GONE
            b.text = "${i + 1}. ${text.orEmpty()}"
        }
        confidenceBox.visibility = if (s.awaiting == ListenContext.CONFIDENCE) View.VISIBLE else View.GONE
        yesNoBox.visibility = if (s.awaiting == ListenContext.YES_NO) View.VISIBLE else View.GONE

        playButton.text = when (s.mode) {
            Mode.SPEAKING, Mode.LISTENING -> "⏸ עצור"
            Mode.PAUSED, Mode.WAITING -> "▶ המשך"
            else -> "▶ התחל נסיעה"
        }
        progressText.text = "הושלמו ${s.completedUnits} מתוך ${s.totalUnits} יחידות · ${s.masteredConcepts} מושגים יושבים"

        val setup = mutableListOf<String>()
        if (s.voiceReady && !s.hebrewVoice) setup += "⚠️ אין קול עברי. התקן את Google Speech Services והורד עברית, ואז הפעל מחדש."
        if (s.voiceReady && !s.recognizerReady) setup += "⚠️ זיהוי דיבור לא זמין. אפשר לענות בלחיצות."
        if (s.message.isNotBlank()) setup += s.message
        setupText.text = setup.joinToString("\n")
        setupText.visibility = if (setup.isEmpty()) View.GONE else View.VISIBLE
        englishSwitch.isChecked = s.englishVoiceForTerms

        val active = s.mode == Mode.SPEAKING || s.mode == Mode.LISTENING
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- layout, built in code to keep the project small ----------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }
        root.addView(text("Drive Learning", 26f, bold = true))
        progressText = text("", 14f, color = GREY).also { root.addView(it) }
        setupText = text("", 14f, color = Color.parseColor("#B74B4B")).also { root.addView(it) }

        status = text("", 16f, color = ACCENT, bold = true).also { root.addView(it, top(16)) }
        unitTitle = text("", 15f, color = GREY).also { root.addView(it) }
        label = text("", 22f, bold = true).also { root.addView(it, top(8)) }
        prompt = text("", 19f).also { root.addView(it, top(8)) }
        heard = text("", 13f, color = GREY).also { root.addView(it) }

        optionsBox = vertical().also { root.addView(it, top(8)) }
        repeat(4) { i ->
            val b = button("") { controller.onCommand(Command.Answer(i + 1)) }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            optionButtons += b
            optionsBox.addView(b, full())
        }
        confidenceBox = horizontal(
            button("👍 בטוח") { controller.onCommand(Command.Sure(true)) },
            button("🤔 לא בטוח") { controller.onCommand(Command.Sure(false)) },
        ).also { root.addView(it, top(8)) }
        yesNoBox = horizontal(
            button("כן, תשמיע שוב") { controller.onCommand(Command.YesNo(true)) },
            button("לא, תמשיך") { controller.onCommand(Command.YesNo(false)) },
        ).also { root.addView(it, top(8)) }

        playButton = button("▶ התחל נסיעה") {
            when (controller.state.value.mode) {
                Mode.SPEAKING, Mode.LISTENING -> controller.pause()
                Mode.PAUSED, Mode.WAITING -> controller.resume()
                else -> controller.start()
            }
        }.also { it.textSize = 20f }
        root.addView(playButton, top(20))
        root.addView(horizontal(
            button("↺ תחזור") { controller.onCommand(Command.Replay) },
            button("⏮ אחורה") { controller.onCommand(Command.Back) },
            button("⏭ דלג") { controller.onCommand(Command.Forward) },
        ))
        root.addView(horizontal(
            button("💡 תסביר אחרת") { controller.onCommand(Command.Simpler) },
            button("🔍 תעמיק") { controller.onCommand(Command.Deeper) },
            button("רמז") { controller.onCommand(Command.Hint) },
        ))
        root.addView(horizontal(
            button("🎤 פקודה קולית") { controller.listenNow() },
            button("סיכום") { controller.onCommand(Command.Recap) },
            button("⏹ סיים") { controller.stop() },
        ))

        root.addView(text("הגדרות", 18f, bold = true), top(28))
        englishSwitch = Switch(this).apply {
            text = "לקרוא מונחים באנגלית בקול אנגלי"
            setOnCheckedChangeListener { _, checked -> controller.setEnglishVoiceForTerms(checked) }
        }
        root.addView(englishSwitch, top(4))
        root.addView(horizontal(
            button("⬇ עדכן תוכן") { controller.sync() },
            button("הגדרות הקראה") { openTtsSettings() },
        ))
        root.addView(
            text(
                "פקודות קוליות בכל שאלה: תחזור, מההתחלה, אחורה, קדימה, תסביר אחרת, תעמיק, " +
                    "מה זה…, רמז, תחזור על השאלה, עצור, המשך, סיכום, חזרה על יחידה…, יש לי שאלה…",
                13f, color = GREY,
            ),
            top(12),
        )
        return ScrollView(this).apply { addView(root) }
    }

    private fun openTtsSettings() {
        runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
    }

    private fun text(value: String, size: Float, color: Int = Color.parseColor("#1F2328"), bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            textDirection = View.TEXT_DIRECTION_RTL
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(value: String, onClick: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun horizontal(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun top(margin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(margin) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val ACCENT = Color.parseColor("#2F6F5E")
        private val GREY = Color.parseColor("#5F6670")
    }
}

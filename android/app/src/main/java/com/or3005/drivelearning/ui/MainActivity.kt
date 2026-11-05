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
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.or3005.drivelearning.PlaybackService
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
    private lateinit var tokenField: EditText
    private lateinit var uploadText: TextView
    private lateinit var geminiText: TextView
    private val optionButtons = mutableListOf<Button>()
    private lateinit var coursesBox: LinearLayout
    private var shownCourses: List<LearningController.CourseChoice> = emptyList()
    private var shownCourseId = ""

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startStandbyService()
    }

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

    override fun onStart() {
        super.onStart()
        startStandbyService()
    }

    /**
     * Starting the service while this screen is visible is what allows voice answers later, when
     * the lesson runs from the car with the phone locked.
     */
    private fun startStandbyService() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted || PlaybackService.running) return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java)) }
    }

    /** The Gemini key goes from the clipboard straight to the phone's storage, never to the chat or the repo. */
    private fun pasteGeminiKey() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        controller.setGeminiKey(text)
    }

    /** One-time sign-in: copies the code and opens GitHub's page for it. */
    private fun loginToGithub() {
        controller.loginToGithub { code, url ->
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("GitHub code", code))
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        }
    }

    /** Reads the token straight from the clipboard, so a paste that didn't land in the field can't lose it. */
    private fun pasteToken() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        tokenField.setText(text.trim())
        saveToken(text)
    }

    private fun saveToken(text: String) {
        if (controller.setGithubToken(text)) tokenField.setText("")
    }

    private fun shareProgress() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Drive Learning progress")
            putExtra(Intent.EXTRA_TEXT, controller.progressText())
        }
        runCatching { startActivity(Intent.createChooser(send, "שליחת התקדמות")) }
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
    }

    /** One button per course; the chosen one is marked. Rebuilt only when the list or the choice changes. */
    private fun renderCourses(s: LearningController.UiState) {
        if (s.courses == shownCourses && s.courseId == shownCourseId) return
        shownCourses = s.courses
        shownCourseId = s.courseId
        coursesBox.removeAllViews()
        s.courses.forEach { c ->
            val chosen = c.id == s.courseId
            val b = button((if (chosen) "✓ " else "") + "${c.title} · ${c.completed}/${c.total}") {
                controller.selectCourse(c.id)
            }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            if (chosen) b.setTextColor(ACCENT)
            b.setTypeface(b.typeface, if (chosen) Typeface.BOLD else Typeface.NORMAL)
            coursesBox.addView(b, full())
        }
    }

    private fun render(s: LearningController.UiState) {
        renderCourses(s)
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
        if (!PlaybackService.microphoneReady) setup += "🎤 כדי לענות בקול ברכב, פתח את האפליקציה בטלפון לפני הנסיעה."
        if (s.message.isNotBlank()) setup += s.message
        setupText.text = setup.joinToString("\n")
        setupText.visibility = if (setup.isEmpty()) View.GONE else View.VISIBLE
        englishSwitch.isChecked = s.englishVoiceForTerms
        val spent = String.format(java.util.Locale.US, "%.2f", s.geminiSpent)
        val cap = String.format(java.util.Locale.US, "%.0f", s.geminiCap)
        geminiText.text = when {
            !s.geminiConfigured -> "כבוי. עם מפתח מ-Google AI Studio תקבל משוב מיד על ההסברים שלך, ותשובות כשתגיד: יש לי שאלה."
            else -> "פעיל" + (if (s.geminiModel.isBlank()) "" else " · ${s.geminiModel}") +
                "\nהחודש: בערך $spent דולר מתוך תקרה של $cap (הקרדיט החודשי הוא 10)"
        }
        uploadText.text = when {
            s.uploadError.isNotBlank() -> "⚠️ השליחה ל-GitHub נכשלה: ${s.uploadError}"
            !s.uploadConfigured -> "שליחה אוטומטית ל-GitHub: כבויה (אין טוקן)"
            s.lastUpload.isBlank() -> "שליחה אוטומטית ל-GitHub: פעילה, עוד לא נשלח"
            else -> "שליחה אוטומטית ל-GitHub: פעילה · נשלח לאחרונה ${s.lastUpload}"
        }

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
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        root.addView(text("גרסה $version", 13f, color = GREY))
        root.addView(text("הנושא לנסיעה הקרובה", 15f, bold = true), top(14))
        coursesBox = vertical().also { root.addView(it, top(4)) }
        progressText = text("", 14f, color = GREY).also { root.addView(it, top(4)) }
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
            button("⏪ 15 שניות") { controller.onCommand(Command.Rewind) },
            button("⏩ 15 שניות") { controller.onCommand(Command.FastForward) },
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

        root.addView(text("התקדמות", 18f, bold = true), top(28))
        uploadText = text("", 13f, color = GREY).also { root.addView(it) }
        tokenField = EditText(this).apply {
            hint = "הדבק כאן טוקן של GitHub (מתחיל ב-github_pat_)"
            // Visible, so it's easy to see that the whole token was pasted.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        root.addView(tokenField, top(4))
        root.addView(horizontal(
            button("🔗 התחברות ל-GitHub") { loginToGithub() },
        ))
        root.addView(text("או, אם יש לך טוקן מוכן:", 13f, color = GREY), top(8))
        root.addView(horizontal(
            button("📋 הדבק ושמור") { pasteToken() },
            button("💾 שמור") { saveToken(tokenField.text.toString()) },
        ))
        root.addView(horizontal(
            button("⬆ שלח עכשיו") { controller.uploadNow(report = true) },
            button("📤 שתף") { shareProgress() },
        ))
        root.addView(text("עזרה חיה מ-Gemini (לא חובה)", 18f, bold = true), top(28))
        geminiText = text("", 13f, color = GREY).also { root.addView(it) }
        root.addView(horizontal(
            button("📋 הדבק מפתח Gemini") { pasteGeminiKey() },
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

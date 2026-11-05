package com.or3005.drivelearning

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.or3005.drivelearning.audio.Beeper
import com.or3005.drivelearning.audio.Listener
import com.or3005.drivelearning.audio.Speaker
import com.or3005.drivelearning.content.ContentRepository
import com.or3005.drivelearning.engine.Command
import com.or3005.drivelearning.engine.CommandParser
import com.or3005.drivelearning.engine.FileProgressStore
import com.or3005.drivelearning.engine.HebrewNumbers
import com.or3005.drivelearning.engine.LessonEngine
import com.or3005.drivelearning.engine.ListenContext
import com.or3005.drivelearning.engine.Sentences
import com.or3005.drivelearning.engine.Step
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Runs a drive: speaks the engine's steps, listens for answers and commands, and publishes a
 * [UiState] for the phone screen and the car. Everything runs on the main thread.
 */
class LearningController(private val app: Application) {

    enum class Mode { IDLE, SPEAKING, LISTENING, WAITING, PAUSED, FINISHED }

    data class UiState(
        val mode: Mode = Mode.IDLE,
        val unitTitle: String = "",
        val label: String = "",
        val prompt: String = "",
        val options: List<String> = emptyList(),
        val awaiting: ListenContext? = null,
        val heard: String = "",
        val message: String = "",
        val voiceReady: Boolean = false,
        val hebrewVoice: Boolean = false,
        val recognizerReady: Boolean = false,
        val completedUnits: Int = 0,
        val totalUnits: Int = 0,
        val masteredConcepts: Int = 0,
        val englishVoiceForTerms: Boolean = true,
        val uploadConfigured: Boolean = false,
        val lastUpload: String = "",
        val uploadError: String = "",
        val courseId: String = "",
        val courseTitle: String = "",
        val courses: List<CourseChoice> = emptyList(),
        val geminiConfigured: Boolean = false,
        val geminiModel: String = "",
    )

    /** A course on the picker: its progress so far. */
    data class CourseChoice(val id: String, val title: String, val completed: Int, val total: Int)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val repo = ContentRepository(app)
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** The course for the coming drive. Each course keeps its own progress file. */
    private var courseId: String = prefs.getString(KEY_COURSE, null) ?: ContentRepository.DEFAULT_COURSE
    private var progressFile = progressFileFor(courseId)
    private var store = FileProgressStore(progressFile)

    private fun progressFileFor(id: String): File {
        val file = File(app.filesDir, "progress-$id.json")
        // Before there were several courses, the only progress file was progress.json.
        val legacy = File(app.filesDir, "progress.json")
        if (id == ContentRepository.DEFAULT_COURSE && !file.exists() && legacy.exists()) legacy.renameTo(file)
        return file
    }
    private val uploader = ProgressUploader(app)
    private val gemini = GeminiTutor(app)
    private val listener = Listener(app)
    private val speaker = Speaker(app) { hebrew ->
        _state.update { it.copy(voiceReady = true, hebrewVoice = hebrew, recognizerReady = listener.available) }
    }

    private var engine: LessonEngine? = null

    /** Index of the sentence being spoken in the current Say step, for pause, resume and seeking. */
    private var sentence = 0
    private var resumeTarget: Step.Say? = null
    private var resumeSentence = 0
    private var listenTries = 0
    private var uploadJob: Job? = null

    /**
     * True while the speech recognizer is listening. The recognizer takes audio focus itself, and
     * that must not be mistaken for Waze: pausing would cancel the recognizer, which gives focus
     * back, which resumes and listens again, in a loop.
     */
    private var listening = false

    /** A long explain-back answer is collected over several recognizer sessions. */
    private val explainBuffer = StringBuilder()
    private var explainRounds = 0
    private var explainErrors = 0
    private var explainSilences = 0
    /** A dropped recognizer connection gets one quiet retry before it counts as a failure. */
    private var transientRetried = false

    // Audio focus: pause while Waze or a phone call talks, continue when it's done.
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private var pausedByInterruption = false
    private var listeningWhenInterrupted: ListenContext? = null
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Speech shouldn't be ducked under navigation prompts. Ask the system to tell us instead,
        // and pause, like a podcast app does.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change -> main.post { onFocusChange(change) } }
        .build()

    init {
        speaker.pronunciation = repo.loadPronunciation()
        val englishVoice = prefs.getBoolean(KEY_ENGLISH_VOICE, true)
        speaker.englishVoiceForTerms = englishVoice
        _state.update { it.copy(englishVoiceForTerms = englishVoice) }
        refreshCounts()
        refreshUploadState()
        refreshGemini()
        // New units, pronunciation fixes and settings arrive without a new APK.
        scope.launch {
            repo.sync().onSuccess {
                speaker.pronunciation = repo.loadPronunciation()
                refreshCounts()
            }
        }
    }

    // ---- public API used by the phone screen and the car ------------------------------------------

    /** Chooses the course for the coming drive. A drive in another course is stopped first. */
    fun selectCourse(id: String) {
        if (id == courseId) return
        if (repo.courses().none { it.id == id }) return
        if (engine != null) stop()
        courseId = id
        prefs.edit().putString(KEY_COURSE, id).apply()
        progressFile = progressFileFor(id)
        store = FileProgressStore(progressFile)
        refreshCounts()
    }

    fun start() {
        val loaded = runCatching { repo.load(courseId) }.getOrElse {
            message("לא הצלחתי לטעון את התוכן: ${it.message}")
            return
        }
        val e = LessonEngine(loaded.units, store, LocalDate.now(), feedback = loaded.feedback)
        engine = e
        requestFocus()
        startService()
        e.start()
        run()
    }

    fun pause() {
        if (engine == null) return
        interrupt()
        resumeTarget = engine?.current as? Step.Say
        resumeSentence = sentence
        _state.update { it.copy(mode = Mode.PAUSED, awaiting = null) }
        uploadNow()
    }

    fun resume() {
        val e = engine ?: return start()
        if (e.current is Step.End) return start()
        requestFocus()
        run()
    }

    fun stop() {
        interrupt()
        abandonFocus()
        engine = null
        stopService()
        _state.update { it.copy(mode = Mode.IDLE, awaiting = null, label = "", prompt = "", options = emptyList()) }
        refreshCounts()
        uploadNow()
    }

    /** Mic button: listen for an answer if one is expected, otherwise for a command. */
    fun listenNow() {
        val e = engine ?: return
        val ctx = contextFor(e.current) ?: ListenContext.COMMAND
        if (ctx == ListenContext.COMMAND) {
            resumeTarget = e.current as? Step.Say
            resumeSentence = sentence
        }
        interrupt()
        listen(ctx)
    }

    fun onCommand(command: Command) {
        val e = engine
        if (e == null) {
            if (command is Command.Resume) start()
            return
        }
        val interruptedSay = e.current as? Step.Say
        val at = sentence
        interrupt()
        listenTries = 0
        when (command) {
            is Command.Answer -> if (!e.answer(command.n)) return retryListen()
            is Command.Sure -> if (e.confidence(command.sure)) uploadSoon() else return retryListen()
            is Command.YesNo -> if (!e.replayAnswer(command.yes)) return retryListen()
            is Command.Explain -> {
                val ask = e.current as? Step.Ask
                if (gemini.configured && ask != null && ask.question.isExplain && command.transcript.isNotBlank()) {
                    gradeLive(e, ask, command.transcript)
                    return
                }
                e.explain(command.transcript)
                uploadSoon()
            }
            is Command.Replay -> if (interruptedSay != null) resumeAt(interruptedSay, (at - 2).coerceAtLeast(0))
            is Command.RestartSegment -> if (interruptedSay != null) resumeAt(interruptedSay, 0)
            is Command.Rewind -> rewind(e, interruptedSay, at)
            is Command.FastForward -> fastForward(e, interruptedSay, at)
            is Command.Back -> e.back()
            is Command.Forward -> e.skip()
            is Command.Simpler -> insert(e, e.simplerSteps(), interruptedSay, at, resume = true)
            is Command.Deeper -> insert(e, e.deeperSteps(), interruptedSay, at, resume = true)
            is Command.Hint -> insert(e, e.hintSteps(), interruptedSay, at, resume = true)
            is Command.Recap -> insert(e, e.recapSteps(), interruptedSay, at, resume = true)
            is Command.Define -> insert(e, e.defineSteps(command.query), interruptedSay, at, resume = true)
            is Command.RepeatQuestion -> Unit // run() below asks the current question again
            is Command.Pause -> {
                resumeTarget = interruptedSay
                resumeSentence = at
                _state.update { it.copy(mode = Mode.PAUSED, awaiting = null) }
                uploadNow()
                return
            }
            is Command.Resume -> if (interruptedSay != null) resumeAt(interruptedSay, at)
            is Command.LiveQuestion -> {
                e.saveLiveQuestion(command.text)
                uploadSoon()
                if (gemini.configured && command.text.isNotBlank()) {
                    answerLive(e, command.text, interruptedSay, at)
                    return
                }
                insert(
                    e,
                    listOf(Step.Say("שמרתי את השאלה. אענה עליה בנסיעה הבאה.", e.current.unitId, label = "שאלה")),
                    interruptedSay, at, resume = true,
                )
            }
            is Command.ReplayUnit -> if (!e.replayUnit(command.number)) {
                insert(e, listOf(Step.Say("אין יחידה כזאת.")), interruptedSay, at, resume = true)
            }
        }
        run()
    }

    fun setEnglishVoiceForTerms(enabled: Boolean) {
        speaker.englishVoiceForTerms = enabled
        prefs.edit().putBoolean(KEY_ENGLISH_VOICE, enabled).apply()
        _state.update { it.copy(englishVoiceForTerms = enabled) }
    }

    fun sync() {
        message("מעדכן תוכן…")
        scope.launch {
            repo.sync()
                .onSuccess { n ->
                    speaker.pronunciation = repo.loadPronunciation()
                    message("התוכן עודכן: $n יחידות.")
                    refreshCounts()
                }
                .onFailure { message("העדכון נכשל: ${it.message}") }
        }
    }

    private var loginJob: Job? = null

    /**
     * One-time GitHub sign-in with a code. [onCode] gets the code and the page to type it in; the
     * screen copies the code and opens the page. When it's approved, uploads start working on their own.
     */
    fun loginToGithub(onCode: (code: String, url: String) -> Unit) {
        loginJob?.cancel()
        loginJob = scope.launch {
            // The client id comes with the content; fetch it if this phone doesn't have it yet.
            var clientId = repo.loadConfig().githubClientId
            if (clientId.isBlank()) {
                message("מביא הגדרות…")
                repo.sync()
                clientId = repo.loadConfig().githubClientId
            }
            if (clientId.isBlank()) {
                message("ההתחברות עוד לא מוגדרת: חסר Client ID. אפשר בינתיים לשתף את ההתקדמות.")
                return@launch
            }
            val login = GitHubLogin(clientId)
            val code = login.requestCode().getOrElse {
                message("ההתחברות נכשלה: ${it.message}")
                return@launch
            }
            message("הקוד ${code.userCode} הועתק. הדבק אותו בדף של GitHub ואשר. אחר כך חזור לכאן.")
            onCode(code.userCode, code.verificationUri)
            login.waitForToken(code)
                .onSuccess { token ->
                    uploader.token = token
                    refreshUploadState()
                    message("מחובר ל-GitHub. ההתקדמות תישלח לבד אחרי כל נסיעה. שולח עכשיו…")
                    uploadNow(report = true)
                }
                .onFailure { message("ההתחברות לא הושלמה: ${it.message}") }
        }
    }

    // ---- live help from Gemini ------------------------------------------------------------------------

    /** Saves the learner's own Gemini API key (kept on the phone only) and checks it. */
    fun setGeminiKey(raw: String): Boolean {
        val key = raw.trim()
        if (key.length < 20 || key.any { it.isWhitespace() }) {
            message("זה לא נראה כמו מפתח של Gemini. מעתיקים אותו מ-Google AI Studio, ולוחצים שוב.")
            return false
        }
        gemini.key = key
        refreshGemini()
        message("בודק את המפתח…")
        scope.launch {
            gemini.check(repo.loadConfig().geminiModel)
                .onSuccess { m -> message("Gemini מחובר ($m). בנסיעה תקבל משוב מיד על ההסברים, ותשובות לשאלות.") }
                .onFailure { message("המפתח נשמר, אבל הבדיקה נכשלה: ${it.message}") }
            refreshGemini()
        }
        return true
    }

    private fun refreshGemini() {
        _state.update { it.copy(geminiConfigured = gemini.configured, geminiModel = gemini.model) }
    }

    /** Grades an explain-back answer with Gemini and plays the feedback now. Falls back to grading after the drive. */
    private fun gradeLive(e: LessonEngine, ask: Step.Ask, transcript: String) {
        _state.update { it.copy(mode = Mode.SPEAKING, awaiting = null, label = "בודק את ההסבר") }
        speaker.speak("רגע, בודק את ההסבר שלך.") {}
        scope.launch {
            val q = ask.question
            val result = gemini.gradeExplain(q.prompt, q.rubric, e.lessonTextFor(ask.unitId, q), transcript, repo.loadConfig().geminiModel)
            if (engine !== e || e.current !== ask) return@launch // the learner moved on meanwhile
            result.onFailure { e.logDiagnostic("gemini: ${it.message}") }
            val grade = result.getOrNull()
            e.explain(transcript, grade?.feedback, grade?.covered)
            uploadSoon()
            run()
        }
    }

    /** Answers a question asked out loud, then the lesson continues where it stopped. */
    private fun answerLive(e: LessonEngine, question: String, interrupted: Step.Say?, at: Int) {
        val context = e.liveContext()
        _state.update { it.copy(mode = Mode.SPEAKING, label = "השאלה שלך") }
        speaker.speak("שאלה טובה, רגע.") {}
        scope.launch {
            val result = gemini.answer(question, context, repo.loadConfig().geminiModel)
            if (engine !== e) return@launch
            val text = result.getOrElse {
                e.logDiagnostic("gemini: ${it.message}")
                "לא הצלחתי לענות עכשיו. שמרתי את השאלה, ואענה עליה אחרי הנסיעה."
            }
            if (result.isSuccess) e.saveLiveAnswer(question, text)
            insert(e, listOf(Step.Say(text, e.current.unitId, label = "תשובה לשאלה שלך")), interrupted, at, resume = true)
            run()
        }
    }

    /** Saves the token if it looks like a GitHub token, and tries an upload right away. Returns whether it was saved. */
    fun setGithubToken(raw: String): Boolean {
        val token = raw.trim()
        val problem = when {
            token.isEmpty() -> "השדה ריק. העתק את הטוקן מ-GitHub, ואז לחץ הדבק ושמור."
            !(token.startsWith("github_pat_") || token.startsWith("ghp_") || token.startsWith("gho_")) ->
                "זה לא נראה כמו טוקן של GitHub. טוקן מתחיל ב-github_pat_ ."
            token.length < 40 || token.any { it.isWhitespace() } ->
                "הטוקן נראה חתוך או עם רווחים. העתק אותו שוב, בשלמותו."
            else -> null
        }
        if (problem != null) {
            message(problem)
            return false
        }
        uploader.token = token
        refreshUploadState()
        message("הטוקן נשמר (${token.take(15)}…). שולח התקדמות…")
        uploadNow(report = true)
        return true
    }

    fun uploadNow(report: Boolean = false) {
        uploadJob?.cancel()
        if (!uploader.configured) {
            if (report) message("כדי לשלוח התקדמות צריך קודם לשמור טוקן של GitHub.")
            return
        }
        uploadJob = scope.launch {
            uploader.upload(progressFile, "progress/$courseId.json")
                .onSuccess {
                    _state.update { s -> s.copy(uploadError = "") }
                    if (report) message("ההתקדמות נשלחה.")
                }
                .onFailure { uploadFailed(it, report) }
            refreshUploadState()
        }
    }

    /** The progress file as text, for the share button. */
    fun progressText(): String = runCatching { progressFile.readText() }.getOrDefault("{}")

    // ---- the player loop ---------------------------------------------------------------------------

    private fun run() {
        val e = engine ?: return
        refreshCounts()
        when (val step = e.current) {
            is Step.Say -> {
                val from = if (resumeTarget === step) resumeSentence else 0
                resumeTarget = null
                sentence = from
                show(step, Mode.SPEAKING)
                speaker.speak(step.text, from, onSentence = { i -> sentence = i + 1 }) {
                    sentence = 0
                    e.advance()
                    run()
                }
            }
            is Step.Ask -> {
                show(step, Mode.SPEAKING)
                val q = step.question
                transientRetried = false
                if (q.isExplain) {
                    explainBuffer.clear()
                    explainRounds = 0
                    explainErrors = 0
                    explainSilences = 0
                }
                val text = if (q.isExplain) {
                    "${q.prompt} אחרי הצפצוף, דבר בחופשיות, גם עם הפסקות. כשתסיים, תגיד סיימתי."
                } else {
                    buildString {
                        if (q.isCheck) append("שאלה קצרה לפני שממשיכים. ")
                        append(q.prompt).append(' ')
                        step.displayedOptions.forEachIndexed { i, o -> append("תשובה ${HebrewNumbers.word(i + 1)}: $o ") }
                        append("מה התשובה?")
                    }
                }
                speaker.speak(text) { listen(if (q.isExplain) ListenContext.EXPLAIN else ListenContext.ANSWER) }
            }
            is Step.AskConfidence -> {
                show(step, Mode.SPEAKING)
                speaker.speak("בטוח, או לא בטוח?") { listen(ListenContext.CONFIDENCE) }
            }
            is Step.AskReplay -> {
                show(step, Mode.SPEAKING)
                speaker.speak("רוצה לשמוע שוב את החלק בשיעור שמסביר את זה? כן או לא.") {
                    listen(ListenContext.YES_NO)
                }
            }
            is Step.UnitDone, is Step.Plan -> {
                e.advance()
                run()
            }
            Step.End -> {
                abandonFocus()
                _state.update { it.copy(mode = Mode.FINISHED, awaiting = null, label = "סוף", prompt = "", options = emptyList()) }
                refreshCounts()
                uploadNow()
            }
        }
    }

    private fun listen(ctx: ListenContext, beep: Boolean = true) {
        listening = true
        _state.update { it.copy(mode = Mode.LISTENING, awaiting = ctx.takeIf { c -> c != ListenContext.COMMAND } ?: it.awaiting) }
        if (beep) Beeper.listen()
        // Give the beep a moment so it isn't recorded as speech.
        main.postDelayed({
            listener.listen(
                long = ctx == ListenContext.EXPLAIN,
                minimumMillis = when (ctx) {
                    ListenContext.ANSWER -> 6_000L
                    ListenContext.CONFIDENCE, ListenContext.YES_NO -> 5_000L
                    ListenContext.EXPLAIN -> 10_000L
                    ListenContext.COMMAND -> null
                },
                accept = { partial -> acceptsEarly(partial, ctx) },
            ) { results, error -> heard(results, error, ctx) }
        }, if (beep) 250L else 350L) // without a beep: let the previous recognizer close first
    }

    /** A partial transcript that already settles the answer, so there's no need to wait for silence. */
    private fun acceptsEarly(partial: String, ctx: ListenContext): Boolean = when (ctx) {
        ListenContext.ANSWER -> CommandParser.parse(partial, ctx) is Command.Answer
        ListenContext.YES_NO -> CommandParser.parse(partial, ctx) is Command.YesNo
        ListenContext.CONFIDENCE -> CommandParser.parse(partial, ctx) is Command.Sure
        ListenContext.EXPLAIN -> DONE_WORDS.any { partial.contains(it) }
        ListenContext.COMMAND -> false
    }

    /**
     * Collects an explain-back answer across recognizer sessions: the recognizer stops at every
     * pause, so keep listening until the learner says סיימתי or stays quiet.
     */
    private fun heardExplain(e: LessonEngine, results: List<String>, error: Int?) {
        val text = results.firstOrNull().orEmpty().trim()
        if (explainBuffer.isEmpty() && text.isNotEmpty()) {
            // A navigation command instead of an answer, like רמז or תחזור על השאלה.
            val cmd = CommandParser.parse(text, ListenContext.EXPLAIN)
            if (cmd != null && cmd !is Command.Explain) {
                onCommand(cmd)
                return
            }
        }
        val done = DONE_WORDS.firstOrNull { text.contains(it) }
        val piece = if (done != null) text.substringBefore(done).trim() else text
        if (piece.isNotEmpty()) explainBuffer.append(piece).append(' ')
        explainRounds++
        // A dropped connection to the recognizer is not the end of the answer: start listening again.
        val transient = error != null && error in TRANSIENT_ERRORS && text.isEmpty()
        explainErrors = if (transient) explainErrors + 1 else 0
        explainSilences = if (text.isEmpty() && !transient) explainSilences + 1 else 0
        // Two quiet rounds in a row end the answer (or one, if nothing was said yet and it's the second try).
        val silence = explainSilences >= 2 || (explainSilences >= 1 && explainBuffer.isEmpty() && explainRounds >= 2)
        val problem = !transient && error?.let { Listener.describe(it) } != null
        val gaveUp = explainErrors > MAX_TRANSIENT_RETRIES
        if (done != null || silence || problem || gaveUp || explainRounds >= MAX_EXPLAIN_ROUNDS) {
            val transcript = explainBuffer.toString().trim()
            explainBuffer.clear()
            explainRounds = 0
            explainErrors = 0
            explainSilences = 0
            val reason = done ?: when {
                silence -> "silence"
                problem || gaveUp -> "error $error"
                else -> "limit"
            }
            e.logDiagnostic("explain: ${transcript.length} chars, ended by $reason")
            onCommand(Command.Explain(transcript))
        } else {
            listen(ListenContext.EXPLAIN, beep = false)
        }
    }

    private fun heard(results: List<String>, error: Int?, ctx: ListenContext) {
        listening = false
        val e = engine ?: return
        val problem = error?.let { Listener.describe(it) }
        if (problem != null) {
            message("🎤 $problem")
            e.logDiagnostic("microphone: $problem")
        }
        _state.update { it.copy(heard = results.firstOrNull().orEmpty()) }
        if (ctx == ListenContext.EXPLAIN) {
            heardExplain(e, results, error)
            return
        }
        val command = results.firstNotNullOfOrNull { CommandParser.parse(it, ctx) }
        e.logDiagnostic(
            "listen $ctx: " + if (results.isEmpty()) "nothing (code $error)"
            else "'${results.first()}' -> ${command?.javaClass?.simpleName ?: "not understood"}",
        )
        if (command != null) {
            onCommand(command)
            return
        }
        if (ctx == ListenContext.COMMAND) {
            // Nothing understood: carry on where we stopped.
            (e.current as? Step.Say)?.let { resumeAt(it, resumeSentence) }
            run()
            return
        }
        if (problem != null && error in TRANSIENT_ERRORS && !transientRetried) {
            transientRetried = true
            listen(ctx)
            return
        }
        if (problem != null) {
            // The microphone itself failed. Retrying won't help; wait for a tap.
            _state.update { it.copy(mode = Mode.WAITING, awaiting = ctx) }
            speaker.speak("המיקרופון לא זמין כרגע. אפשר לענות בלחיצה על המסך.") {}
            return
        }
        retryListen()
    }

    private fun retryListen() {
        val e = engine ?: return
        val ctx = contextFor(e.current) ?: return run()
        listenTries++
        if (listenTries > 2) {
            listenTries = 0
            _state.update { it.copy(mode = Mode.WAITING, awaiting = ctx) }
            speaker.speak("אפשר לענות גם בלחיצה על המסך. אני מחכה.") {}
            return
        }
        val help = when (ctx) {
            ListenContext.ANSWER -> "לא הבנתי. תגיד מספר, למשל תשובה שתיים. אפשר גם לבקש רמז."
            ListenContext.CONFIDENCE -> "לא הבנתי. תגיד בטוח, או לא בטוח."
            ListenContext.YES_NO -> "לא הבנתי. תגיד כן, או לא."
            else -> "לא הבנתי."
        }
        _state.update { it.copy(mode = Mode.SPEAKING) }
        speaker.speak(help) { listen(ctx) }
    }

    // ---- seeking: about 15 seconds of speech ------------------------------------------------------

    private fun rewind(e: LessonEngine, say: Step.Say?, at: Int) {
        if (say == null) return // in a question: run() reads it again
        val words = Sentences.split(say.text).map { it.split(' ').size }
        if (words.isEmpty()) return
        var i = at.coerceIn(0, words.lastIndex)
        if (i == 0) {
            // At the start of a segment: go to the previous segment, if there is one.
            if (e.back() === say) resumeAt(say, 0)
            return
        }
        var skipped = 0
        while (i > 0 && skipped < SEEK_WORDS) {
            i--
            skipped += words[i]
        }
        resumeAt(say, i)
    }

    private fun fastForward(e: LessonEngine, say: Step.Say?, at: Int) {
        if (say == null) {
            e.skip()
            return
        }
        val words = Sentences.split(say.text).map { it.split(' ').size }
        var i = at.coerceAtLeast(0)
        var skipped = 0
        while (i < words.size && skipped < SEEK_WORDS) {
            skipped += words[i]
            i++
        }
        if (i >= words.size) e.skip() else resumeAt(say, i)
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private fun insert(e: LessonEngine, steps: List<Step>, interrupted: Step.Say?, at: Int, resume: Boolean) {
        if (interrupted != null && resume) resumeAt(interrupted, at)
        e.insertNow(steps, resumeCurrent = resume)
    }

    private fun resumeAt(say: Step.Say, at: Int) {
        resumeTarget = say
        resumeSentence = at
    }

    private fun interrupt() {
        speaker.stop()
        listening = false
        listener.cancel()
    }

    private fun contextFor(step: Step): ListenContext? = when (step) {
        is Step.Ask -> if (step.question.isExplain) ListenContext.EXPLAIN else ListenContext.ANSWER
        is Step.AskConfidence -> ListenContext.CONFIDENCE
        is Step.AskReplay -> ListenContext.YES_NO
        else -> null
    }

    private fun show(step: Step, mode: Mode) {
        val e = engine ?: return
        val unit = e.unit(step.unitId)
        val unitTitle = unit?.let { e.unitName(it.id) } ?: ""
        _state.update { s ->
            when (step) {
                is Step.Say -> s.copy(mode = mode, unitTitle = unitTitle, label = step.label, prompt = "",
                    options = emptyList(), awaiting = null)
                is Step.Ask -> s.copy(mode = mode, unitTitle = unitTitle,
                    label = when {
                        step.question.isExplain -> "הסבר במילים שלך"
                        step.question.isCheck -> "בדיקה קצרה"
                        else -> "שאלה"
                    },
                    prompt = step.question.prompt, options = step.displayedOptions,
                    awaiting = if (step.question.isExplain) ListenContext.EXPLAIN else ListenContext.ANSWER)
                is Step.AskConfidence -> s.copy(mode = mode, unitTitle = unitTitle, label = "כמה אתה בטוח?",
                    prompt = "בטוח, או לא בטוח?", options = emptyList(), awaiting = ListenContext.CONFIDENCE)
                is Step.AskReplay -> s.copy(mode = mode, unitTitle = unitTitle, label = "לשמוע שוב?",
                    prompt = "לשמוע שוב את החלק שמסביר את זה?", options = emptyList(), awaiting = ListenContext.YES_NO)
                else -> s.copy(mode = mode)
            }
        }
    }

    private fun message(text: String) {
        _state.update { it.copy(message = text) }
    }

    private fun refreshUploadState() {
        _state.update { it.copy(uploadConfigured = uploader.configured, lastUpload = uploader.lastUpload) }
    }

    private fun uploadSoon() {
        if (!uploader.configured) return
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(30_000)
            uploader.upload(progressFile, "progress/$courseId.json")
                .onSuccess { _state.update { s -> s.copy(uploadError = "") } }
                .onFailure { uploadFailed(it, report = false) }
            refreshUploadState()
        }
    }

    private fun uploadFailed(error: Throwable, report: Boolean) {
        val text = error.message ?: error.javaClass.simpleName
        _state.update { it.copy(uploadError = text) }
        engine?.logDiagnostic("upload: $text")
        if (report) message("השליחה נכשלה: $text")
    }

    private fun refreshCounts() {
        val e = engine
        if (e != null) {
            _state.update {
                it.copy(completedUnits = e.progress.completedUnits.size, totalUnits = e.totalUnits,
                    masteredConcepts = e.masteredCount)
            }
            if (_state.value.courses.isEmpty()) refreshCourses()
            return
        }
        runCatching {
            val loaded = repo.load(courseId)
            val progress = store.load()
            _state.update {
                it.copy(completedUnits = progress.completedUnits.count { id -> loaded.units.any { u -> u.id == id } },
                    totalUnits = loaded.units.size, masteredConcepts = progress.concepts.count { c -> c.value.mastered })
            }
        }
        refreshCourses()
    }

    /** The course picker: every course with how far the learner got in it. */
    private fun refreshCourses() {
        val choices = repo.courses().map { c ->
            runCatching {
                val units = repo.load(c.id).units
                val done = FileProgressStore(if (c.id == courseId) progressFile else progressFileFor(c.id)).load().completedUnits
                CourseChoice(c.id, c.title, done.count { id -> units.any { u -> u.id == id } }, units.size)
            }.getOrDefault(CourseChoice(c.id, c.title, 0, 0))
        }
        val title = choices.firstOrNull { it.id == courseId }?.title ?: ""
        _state.update { it.copy(courses = choices, courseId = courseId, courseTitle = title) }
    }

    // ---- audio focus ---------------------------------------------------------------------------------

    private fun requestFocus() {
        pausedByInterruption = false
        audioManager?.requestAudioFocus(focusRequest)
    }

    private fun abandonFocus() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun onFocusChange(change: Int) {
        if (listening) return // our own speech recognizer, not another app
        val s = _state.value
        val active = s.mode == Mode.SPEAKING
        when (change) {
            // Waze prompts and phone calls: pause now, continue when they finish.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (active) {
                listeningWhenInterrupted = null
                pause()
                pausedByInterruption = true
            }
            // Another app started playing: pause and stay paused.
            AudioManager.AUDIOFOCUS_LOSS -> if (active) {
                pause()
                pausedByInterruption = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByInterruption) {
                pausedByInterruption = false
                val ctx = listeningWhenInterrupted
                listeningWhenInterrupted = null
                if (ctx != null && ctx != ListenContext.COMMAND && engine?.let { contextFor(it.current) } == ctx) {
                    listen(ctx)
                } else {
                    resume()
                }
            }
        }
    }

    private fun startService() {
        if (PlaybackService.running) return
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
    }

    private fun stopService() {
        runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
    }

    companion object {
        /** About 15 seconds of Hebrew speech. */
        private const val SEEK_WORDS = 32
        private const val MAX_EXPLAIN_ROUNDS = 12
        private const val MAX_TRANSIENT_RETRIES = 3
        /** Recognizer errors that usually pass on a second try: network hiccups, busy or disconnected service. */
        private val TRANSIENT_ERRORS = setOf(1, 2, 4, 5, 8, 10, 11)
        private val DONE_WORDS = listOf("סיימתי", "סיימנו", "זהו זה", "זה הכל", "זה הכול")
        private const val KEY_ENGLISH_VOICE = "english_voice"
        private const val KEY_COURSE = "current_course"
    }
}

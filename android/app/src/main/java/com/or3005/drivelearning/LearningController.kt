package com.or3005.drivelearning

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.or3005.drivelearning.audio.Beeper
import com.or3005.drivelearning.audio.Listener
import com.or3005.drivelearning.audio.Speaker
import com.or3005.drivelearning.car.CarMic
import com.or3005.drivelearning.content.ContentRepository
import com.or3005.drivelearning.engine.Command
import com.or3005.drivelearning.engine.CommandParser
import com.or3005.drivelearning.engine.FileProgressStore
import com.or3005.drivelearning.engine.HebrewNumbers
import com.or3005.drivelearning.engine.LessonEngine
import com.or3005.drivelearning.engine.ListenContext
import com.or3005.drivelearning.engine.Step
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Runs a drive: speaks the engine's steps, listens for answers and commands, and publishes a
 * [UiState] for the phone screen and the car screen. Everything runs on the main thread.
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
        val englishVoiceForTerms: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val scope = MainScope()
    private val repo = ContentRepository(app)
    private val store = FileProgressStore(File(app.filesDir, "progress.json"))
    private val listener = Listener(app)
    private val speaker = Speaker(app) { hebrew ->
        _state.update { it.copy(voiceReady = true, hebrewVoice = hebrew, recognizerReady = listener.available) }
    }

    private var engine: LessonEngine? = null

    /** Index of the next sentence to speak in the current Say step, for pause, resume and repeat. */
    private var sentence = 0
    private var resumeTarget: Step.Say? = null
    private var resumeSentence = 0
    private var listenTries = 0

    /** Set by the car screen while Android Auto is connected. */
    var carMic: CarMic? = null

    init {
        refreshCounts()
    }

    // ---- public API used by the phone and car screens ----------------------------------------

    fun start() {
        val loaded = runCatching { repo.load() }.getOrElse {
            message("לא הצלחתי לטעון את התוכן: ${it.message}")
            return
        }
        val e = LessonEngine(loaded.units, store, LocalDate.now())
        engine = e
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
    }

    fun resume() {
        val e = engine ?: return start()
        if (e.current is Step.End) return start()
        run()
    }

    fun stop() {
        interrupt()
        engine = null
        stopService()
        _state.update { it.copy(mode = Mode.IDLE, awaiting = null, label = "", prompt = "", options = emptyList()) }
        refreshCounts()
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
            is Command.Sure -> if (!e.confidence(command.sure)) return retryListen()
            is Command.YesNo -> if (!e.replayAnswer(command.yes)) return retryListen()
            is Command.Explain -> e.explain(command.transcript)
            is Command.Replay -> if (interruptedSay != null) {
                resumeAt(interruptedSay, (at - 2).coerceAtLeast(0))
            }
            is Command.RestartSegment -> if (interruptedSay != null) resumeAt(interruptedSay, 0)
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
                return
            }
            is Command.Resume -> if (interruptedSay != null) resumeAt(interruptedSay, at)
            is Command.LiveQuestion -> {
                e.saveLiveQuestion(command.text)
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
        _state.update { it.copy(englishVoiceForTerms = enabled) }
    }

    fun sync() {
        message("מעדכן תוכן…")
        scope.launch {
            repo.sync()
                .onSuccess { n ->
                    message("התוכן עודכן: $n יחידות.")
                    refreshCounts()
                }
                .onFailure { message("העדכון נכשל: ${it.message}") }
        }
    }

    // ---- the player loop -----------------------------------------------------------------------

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
                val text = if (q.isExplain) {
                    "${q.prompt} אחרי הצפצוף, דבר בחופשיות. כשתסיים, פשוט תשתוק כמה שניות."
                } else {
                    buildString {
                        append(q.prompt).append(' ')
                        q.options.forEachIndexed { i, o -> append("תשובה ${HebrewNumbers.word(i + 1)}: ${o.text} ") }
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
            is Step.UnitDone -> {
                e.advance()
                run()
            }
            Step.End -> {
                stopService()
                _state.update { it.copy(mode = Mode.FINISHED, awaiting = null, label = "סוף", prompt = "", options = emptyList()) }
                refreshCounts()
            }
        }
    }

    private fun listen(ctx: ListenContext) {
        _state.update { it.copy(mode = Mode.LISTENING, awaiting = ctx.takeIf { c -> c != ListenContext.COMMAND } ?: it.awaiting) }
        Beeper.listen()
        val mic = carMic
        val audio = mic?.open()
        // Give the beep a moment so it isn't recorded as speech.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            listener.listen(
                long = ctx == ListenContext.EXPLAIN,
                audio = audio,
                onEnd = { mic?.close() },
            ) { results -> heard(results, ctx) }
        }, 250)
    }

    private fun heard(results: List<String>, ctx: ListenContext) {
        val e = engine ?: return
        _state.update { it.copy(heard = results.firstOrNull().orEmpty()) }
        if (ctx == ListenContext.EXPLAIN) {
            val text = results.firstOrNull().orEmpty()
            val cmd = CommandParser.parse(text, ctx) ?: Command.Explain("")
            onCommand(cmd)
            return
        }
        val command = results.firstNotNullOfOrNull { CommandParser.parse(it, ctx) }
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

    // ---- helpers -----------------------------------------------------------------------------------

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
        val unitTitle = unit?.let { "יחידה ${e.unitNumber(it.id)}: ${it.title}" } ?: ""
        _state.update { s ->
            when (step) {
                is Step.Say -> s.copy(mode = mode, unitTitle = unitTitle, label = step.label, prompt = "",
                    options = emptyList(), awaiting = null)
                is Step.Ask -> s.copy(mode = mode, unitTitle = unitTitle,
                    label = if (step.question.isExplain) "הסבר במילים שלך" else "שאלה",
                    prompt = step.question.prompt, options = step.question.options.map { it.text },
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

    private fun refreshCounts() {
        val e = engine
        if (e != null) {
            _state.update {
                it.copy(completedUnits = e.progress.completedUnits.size, totalUnits = e.totalUnits,
                    masteredConcepts = e.masteredCount)
            }
            return
        }
        runCatching {
            val loaded = repo.load()
            val progress = store.load()
            _state.update {
                it.copy(completedUnits = progress.completedUnits.count { id -> loaded.units.any { u -> u.id == id } },
                    totalUnits = loaded.units.size, masteredConcepts = progress.concepts.count { c -> c.value.mastered })
            }
        }
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
    }

    private fun stopService() {
        runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
    }
}

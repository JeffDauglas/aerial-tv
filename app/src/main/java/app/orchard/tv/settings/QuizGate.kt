package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlin.random.Random

/** What the box asks. */
enum class QuizKind(val key: String) {
    /** Two-grade arithmetic: adding, taking away, the times table. */
    Math("math"),

    /** Two-grade English: the word for a thing, the number for a picture. */
    English("english"),

    /** Both, one question after another. */
    Both("both");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Both
    }
}

/** What an English question asks, so that the box can say it in its own language. */
enum class QuizAsk {
    /** The word for what is in the picture. */
    Thing,

    /** How many of them there are. */
    Count,
}

/**
 * A question as the box draws it: the line to read, and the answers to pick from. Arithmetic picks
 * numbers, English picks words; [answer] is the index into [options] in both cases.
 */
data class QuizQuestion(
    /** "27 + 8 = ?"; empty for English, whose line depends on [ask] and is worded by the box. */
    val prompt: String,
    /** What the question is about, shown large above it: a word, or a row of pictures. */
    val subject: String?,
    /** The answers, left to right. */
    val options: List<String>,
    val answer: Int,
    /** For English, what is being asked; null for arithmetic, which asks what [prompt] says. */
    val ask: QuizAsk? = null,
) {
    val right: String get() = options[answer]

    init {
        require(answer in options.indices) { "the answer is one of the options" }
        require(options.size >= 2 && options.size == options.distinct().size) { "options are distinct" }
    }
}

/** The rules chosen; [everyMinutes] 0 is off. */
data class QuizRules(
    val everyMinutes: Int = QuizGate.DEFAULT_EVERY,
    val kind: QuizKind = QuizKind.Both,
    /** Questions to get right before the programme comes back. */
    val correct: Int = 1,
    /** This many wrong lets it on anyway, so that nobody is stuck at a question. */
    val tries: Int = 3,
) {
    val on: Boolean get() = everyMinutes > 0

    val valid: Boolean get() = everyMinutes in 0..QuizGate.MAX_EVERY && correct in 1..5 && tries in 1..5
}

/** Watched since the box last opened, and whether a question was left unanswered. */
data class QuizUsage(
    val watchedMs: Long = 0,
    /** A question was up when Aerial stopped: it opens again rather than a quarter of an hour later. */
    val due: Boolean = false,
)

/**
 * The questions, apart from the clock and the TV so that they can be tested: grade 2 arithmetic
 * (adding and taking away within 100, the times table to 9) and grade 2 English (the word for
 * what is in the picture, and how many).
 */
object QuizTime {
    /** The first question needs no previous timestamp; later ones respect the minimum gap. */
    fun isDue(watchedMs: Long, everyMs: Long, askedAt: Long, now: Long): Boolean =
        watchedMs >= everyMs && (askedAt == 0L || now - askedAt >= QuizGate.MIN_GAP_MS)

    /** Adding and taking away within 100, and the times table: 3 × 4 = ? */
    fun math(random: Random): QuizQuestion {
        val (prompt, answer) = when (random.nextInt(10)) {
            in 0..3 -> {
                val a = random.nextInt(2, 10)
                val b = random.nextInt(2, 10)
                "$a × $b = ?" to a * b
            }
            in 4..6 -> {
                val a = random.nextInt(11, 91)
                val b = random.nextInt(2, 10)
                "$a + $b = ?" to a + b
            }
            in 7..8 -> {
                val a = random.nextInt(21, 100)
                val b = random.nextInt(2, 10)
                "$a − $b = ?" to a - b
            }
            // Two two-digit numbers, adding up to no more than 100. The first stays under 80 so
            // that the second has a range to be drawn from: above 89 there is only 11 left.
            else -> {
                val a = random.nextInt(11, 80)
                val b = random.nextInt(11, 100 - a)
                "$a + $b = ?" to a + b
            }
        }
        val options = optionsAround(answer, random)
        return QuizQuestion(prompt, null, options, options.indexOf(answer.toString()))
    }

    /**
     * English, as a TV can ask it: what a thing is called, and how many there are. The word for a
     * picture, and the number for a row of pictures; both with three answers to pick from.
     */
    fun english(random: Random): QuizQuestion {
        val word = WORDS.random(random)
        return if (random.nextBoolean()) {
            // 🍎 — apple / bird / fish
            val others = WORDS.filter { it.word != word.word }.shuffled(random).take(2).map { it.word }
            val options = (others + word.word).shuffled(random)
            QuizQuestion(
                prompt = "",
                subject = word.emoji,
                options = options,
                answer = options.indexOf(word.word),
                ask = QuizAsk.Thing,
            )
        } else {
            // 🐟🐟🐟 — three / two / four
            val count = random.nextInt(2, 11)
            val near = listOf(count - 1, count + 1, count - 2, count + 2)
                .filter { it in 1..12 }.distinct().shuffled(random).take(2)
            val options = (near + count).shuffled(random).map { NN[it] ?: it.toString() }
            QuizQuestion(
                prompt = "",
                subject = word.emoji.repeat(count),
                options = options,
                answer = options.indexOf(NN[count] ?: count.toString()),
                ask = QuizAsk.Count,
            )
        }
    }

    /** The next question of [kind], from the random source given. */
    fun question(kind: QuizKind, random: Random): QuizQuestion = when (kind) {
        QuizKind.Math -> math(random)
        QuizKind.English -> english(random)
        QuizKind.Both -> if (random.nextBoolean()) math(random) else english(random)
    }

    fun check(q: QuizQuestion, picked: Int): Boolean = q.answer == picked

    /** The answer and three near ones, as numbers: 27 + 8 offers 35, 34, 36, 45 rather than 3 or 300. */
    private fun optionsAround(answer: Int, random: Random): List<String> {
        val step = if (answer >= 40) random.nextInt(2, 6) else random.nextInt(1, 5)
        val others = mutableSetOf<Int>()
        var guard = 0
        while (others.size < 3 && guard++ < 40) {
            val sign = if (random.nextBoolean()) 1 else -1
            val n = answer + sign * step * (1 + random.nextInt(3))
            if (n >= 0 && n != answer) others += n
        }
        // A small answer (0 to 3) has few near ones above zero, so the rest go up from it: 1 offers
        // 2, 3, 4 rather than a shorter row of answers.
        var up = 1
        while (others.size < 3) others += answer + up++
        return (others + answer).map { it.toString() }.shuffled(random)
    }

    /** A thing in a picture and the word for it. */
    private data class Word(val emoji: String, val word: String)

    private val WORDS = listOf(
        Word("🍎", "apple"), Word("🐟", "fish"), Word("🐱", "cat"), Word("🌳", "tree"),
        Word("⭐", "star"), Word("🐦", "bird"), Word("🌸", "flower"), Word("🐘", "elephant"),
        Word("🚗", "car"), Word("🐶", "dog"), Word("🍌", "banana"), Word("🐰", "rabbit"),
        Word("☀", "sun"), Word("🌙", "moon"), Word("📚", "book"), Word("⚽", "ball"),
    )

    /** The numbers spelt out, as grade 2 English does it. */
    private val NN = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    ).withIndex().associate { it.index to it.value }
}

/**
 * Questions between programmes: after [QuizRules.everyMinutes] of watching the picture stops on a
 * question, and the programme goes on once it is answered. Counted in watching time (a still Home,
 * the screensaver and the panels over Home do not count: see QuizWatch), kept across restarts so
 * that turning the TV off and on buys no more.
 *
 * Defaults to [DEFAULT_EVERY] minutes. Personalize, under Kids and Elders mode, or ADB
 * (ConfigReceiver quiz_minutes) can change it; [QuizRules.everyMinutes] 0 turns it off.
 */
object QuizGate {
    private const val PREFS = "quiz"
    private var started = false
    private lateinit var app: Context

    /** A question every quarter of an hour unless another is chosen: what was asked for. */
    const val DEFAULT_EVERY = 15

    /** The longest gap that can be chosen: half an hour of watching. */
    const val MAX_EVERY = 30

    /** Two questions are never closer than this, however the watching was counted. */
    const val MIN_GAP_MS = 5 * 60_000L

    var rules by mutableStateOf(QuizRules()); private set
    var usage by mutableStateOf(QuizUsage()); private set

    /** A question is up and the picture waits for an answer. */
    var due by mutableStateOf(false); private set

    /** When the box last opened (wall clock), so that closing it another way does not reopen it at once. */
    var askedAt by mutableLongStateOf(0L); private set

    /** The question on screen; null when there is none. Made here, so that the box only draws it. */
    var question by mutableStateOf<QuizQuestion?>(null); private set

    /** Answers right and wrong in the box up now. */
    var right by mutableIntStateOf(0); private set
    var wrong by mutableIntStateOf(0); private set

    /** The box is up: Home waits for it, and the programme does not go on. */
    val showing: Boolean get() = due && question != null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rules = QuizRules(
            everyMinutes = p.getInt("every_minutes", DEFAULT_EVERY),
            kind = QuizKind.of(p.getString("kind", null)),
            correct = p.getInt("correct", 1),
            tries = p.getInt("tries", 3),
        ).takeIf { it.valid } ?: QuizRules()
        usage = QuizUsage(watchedMs = p.getLong("watched_ms", 0), due = p.getBoolean("due", false))
        // Kept too, so that a restart does not count as "never asked" and hold the first question
        // back for MIN_GAP_MS.
        askedAt = p.getLong("asked_at", 0)
        // Left unanswered when Aerial stopped (the TV went off, another app took the front): the
        // question comes back with the next look (QuizWatch.tick), not here, where Aerial may still
        // be starting and nothing of it is on screen yet.
    }

    /** A question was left up: it is shown again, once Aerial is where it can be answered. */
    fun resumeLeftQuestion() {
        if (usage.due && rules.on && !showing) show(System.currentTimeMillis())
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun every(): Long = rules.everyMinutes * 60_000L

    fun chooseRules(r: QuizRules) {
        if (!r.valid) return
        rules = r
        prefs().edit {
            putInt("every_minutes", r.everyMinutes); putString("kind", r.kind.key)
            putInt("correct", r.correct); putInt("tries", r.tries)
        }
        when {
            !r.on -> {
                due = false
                question = null
                saveUsage(QuizUsage())
            }
            // A question already up stays as it is; another kind makes the next one.
            showing -> Unit
            // On, or a shorter gap: a full interval of watching before the first question.
            else -> saveUsage(usage.copy(watchedMs = usage.watchedMs.coerceAtMost(every())))
        }
    }

    private fun saveUsage(u: QuizUsage) {
        usage = u
        prefs().edit {
            putLong("watched_ms", u.watchedMs)
            putBoolean("due", u.due)
            putLong("asked_at", askedAt)
        }
    }

    /** When the box last opened, in memory and on disk (so that a restart keeps MIN_GAP_MS). */
    private fun stamp(now: Long) {
        askedAt = now
        prefs().edit { putLong("asked_at", now) }
    }

    /**
     * [ms] more of watching, at [now] (wall clock). True when this is what opened the box: the
     * caller stops the picture. Counted in watching only, as Kids mode counts it.
     */
    fun count(ms: Long, now: Long): Boolean {
        if (!rules.on) return false
        if (showing) return false
        val every = every()
        val next = (usage.watchedMs + ms).coerceAtMost(every)
        if (QuizTime.isDue(next, every, askedAt, now)) {
            show(now)
            return true
        }
        saveUsage(usage.copy(watchedMs = next))
        return false
    }

    /** Puts the question up, at [now]. */
    fun show(now: Long) {
        stamp(now)
        right = 0
        wrong = 0
        question = QuizTime.question(rules.kind, Random(now))
        due = true
        saveUsage(usage.copy(watchedMs = 0, due = true))
    }

    /**
     * The answer picked, at [now]. True when that was the last one and the box closes: right
     * enough ([QuizRules.correct]), or wrong as often as the rules allow ([QuizRules.tries], so
     * that nobody is stuck). False when the box stays up with the next question.
     */
    fun answer(picked: Int, now: Long): Boolean {
        val q = question ?: return true
        if (QuizTime.check(q, picked)) {
            right++
            if (right >= rules.correct) {
                done()
                return true
            }
            // More than one right to go: the next question, and a wrong answer already made here
            // still counts toward being let on.
            question = QuizTime.question(rules.kind, Random(now))
            return false
        }
        wrong++
        if (wrong >= rules.tries) {
            done()
            return true
        }
        question = QuizTime.question(rules.kind, Random(now))
        return false
    }

    /** Answered, or let on: the box closes and the programme goes on. */
    fun done() {
        due = false
        question = null
        right = 0
        wrong = 0
        // From here, a full interval of watching, and no box for MIN_GAP_MS whatever happens.
        stamp(System.currentTimeMillis())
        saveUsage(usage.copy(watchedMs = 0, due = false))
    }

    /** A question every [minutes] of watching; 0 turns them off. */
    fun every(minutes: Int) = chooseRules(rules.copy(everyMinutes = minutes.coerceIn(0, MAX_EVERY)))

    /** Off for good, from ADB (ConfigReceiver quiz_off). */
    fun turnOff() = chooseRules(rules.copy(everyMinutes = 0))

    /** Minutes of watching before the next question, or null when none is coming. */
    fun leftMs(): Long? {
        if (!rules.on) return null
        val every = every()
        return (every - usage.watchedMs).coerceIn(0, every)
    }
}

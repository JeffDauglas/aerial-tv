package app.orchard.tv.guard

import android.content.Context
import android.content.Intent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.dream.AerialDream
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.QuizGate
import app.orchard.tv.settings.QuizRules
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.home.HomeVideo
import app.orchard.tv.ui.home.LiveFilm

/**
 * The questions between programmes (QuizGate), while the TV is on: counts watching, and every
 * [QuizRules.everyMinutes] of it puts a question up and brings Aerial to the front, so that the box
 * is answered on Home.
 *
 * Watching is counted as Kids mode counts it for a break: wherever the TV is on and Aerial's own
 * Home or screensaver is not what is being watched. That is an app in front (a programme, an
 * input's player), or Home with something moving on it. A still Home, the screensaver and standby
 * add nothing; nor do the panels opened over Home (see [watching]).
 *
 * HomeScreen explicitly pauses its wallpapers while the question is visible. Other apps are
 * brought behind Home and returned to their task after answering; playback is owned by that app.
 *
 * Runs in Aerial's process on the main thread, started by Home and by Home Guard, whichever comes
 * first, as KidsWatch does.
 */
object QuizWatch {
    private const val TICK_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var ticking = false
    private var lastTickAt = 0L
    private var broughtBackAt = Long.MIN_VALUE / 2
    private var interruptedPackage: String? = null
    private var returnCaptured = false

    /** Return to the existing app task without replaying a deep link or restarting its player. */
    fun onAnswered() {
        val ctx = app ?: return
        val pkg = interruptedPackage
        interruptedPackage = null
        returnCaptured = false
        if (pkg != null && !KidsWatch.keepsAway(ctx, pkg) && !HomeGuardService.isStockHome(pkg)) {
            runCatching {
                val pm = ctx.packageManager
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    ctx.startActivity(intent)
                }
            }.onFailure { DiagLog.i("quiz: could not return to $pkg: $it") }
        }
        poke()
    }

    private fun captureReturn(ctx: Context) {
        if (returnCaptured) return
        returnCaptured = true
        interruptedPackage = if (homeInFront) null else runCatching {
            val observed = HomeGuardService.foregroundPackage()
            if (observed != null && observed != ctx.packageName) return@runCatching observed
            if (!HomeGuardService.usageStatsAllowed(ctx)) return@runCatching null
            val now = System.currentTimeMillis()
            val events = ctx.getSystemService<UsageStatsManager>()?.queryEvents(now - 86_400_000L, now)
                ?: return@runCatching null
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.packageName
            }
            last?.takeUnless { it == ctx.packageName }
        }.getOrNull()
    }

    /** Aerial's Home is resumed (MainActivity). */
    var homeInFront = false
        set(value) {
            field = value
            if (app != null) handler.post { tick() }
        }

    /** Home has a panel over it (HomeScreen): setting the TV up, which is not watching. */
    var panelOpen = false
        set(value) {
            field = value
            if (app != null) handler.post { tick() }
        }

    private val loop = Runnable { tickAndPlan() }

    fun start(context: Context) {
        if (app == null) {
            app = context.applicationContext
            AerialSettings.init(context)
            QuizGate.init(context)
        }
        poke()
    }

    /** The rules changed, or Aerial came back: look again now, and keep looking while questions are on. */
    fun poke() {
        if (app == null) return
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    /** A question at once, as if the minutes were up (ConfigReceiver quiz_now). */
    fun askNow(context: Context) {
        start(context)
        val ctx = app ?: context.applicationContext
        captureReturn(ctx)
        QuizGate.init(ctx)
        QuizGate.show(System.currentTimeMillis())
        if (!homeInFront) bringBack(ctx, "a question was asked for")
    }

    private fun tickAndPlan() {
        tick()
        if (!QuizGate.rules.on && !QuizGate.showing) {
            ticking = false
            return
        }
        ticking = true
        handler.removeCallbacks(loop)
        handler.postDelayed(loop, TICK_MS)
    }

    private fun tick() {
        val ctx = app ?: return
        if (!QuizGate.showing && !QuizGate.usage.due) {
            interruptedPackage = null
            returnCaptured = false
        }
        val now = System.currentTimeMillis()
        val at = SystemClock.elapsedRealtime()
        val since = if (lastTickAt == 0L) 0L else (at - lastTickAt).coerceAtLeast(0L)
        lastTickAt = at
        val interactive = ctx.getSystemService<PowerManager>()?.isInteractive != false
        // A question left up when Aerial stopped comes back with the first look at Home, not at
        // the start where nothing of Home is on screen yet.
        if (homeInFront && interactive) QuizGate.resumeLeftQuestion()
        if (QuizGate.rules.on && interactive && !AerialDream.showing && watching()) {
            // A gap longer than two ticks is the process held up (standby), not watching.
            val asked = QuizGate.count(since.coerceAtMost(2 * TICK_MS), now)
            if (asked) {
                DiagLog.i("quiz: a question every ${QuizGate.rules.everyMinutes} min of watching")
                // Answered in an app: Aerial comes back for the box. Home has it already.
                if (!homeInFront) bringBack(ctx, "a question is due")
            }
        }
        // A question left up while Aerial is behind an app (the guard could not bring it back, or
        // an app was opened over it): keep asking.
        if (QuizGate.showing && interactive && !homeInFront) bringBack(ctx, "a question waits")
    }

    /**
     * Something is being watched: another app in front, or Home with something moving on it. The
     * owner's own video wallpaper cannot say whether it moves (HomeVideo keeps no such state, it
     * stops with everything else while a panel or a question is over Home), so it counts while it
     * is Home that is showing. Panels over Home are not watching, though: a quarter of an hour
     * spent in the settings should not stop the picture the owner just came back to watch.
     */
    private fun watching(): Boolean {
        if (!homeInFront) return true
        if (panelOpen) return false
        return LiveFilm.onHome?.moving == true || HomeVideo.onHome != null
    }

    private fun bringBack(ctx: Context, why: String) {
        captureReturn(ctx)
        val at = SystemClock.uptimeMillis()
        if (at - broughtBackAt < 3_000) return
        broughtBackAt = at
        DiagLog.i("quiz: bringing Aerial back, $why")
        if (HomeGuardService.bringAerialBack()) return
        runCatching { ctx.startActivity(MainActivity.bringBackIntent(ctx)) }
            .onFailure { DiagLog.i("quiz: could not bring Aerial back: $it") }
    }

    /** For the diagnostics (MainActivity.dump). */
    fun diagnosticState(): String {
        val g = QuizGate
        if (!g.rules.on) return "Quiz off"
        val left = g.leftMs()?.let { "${it / 60_000} min" } ?: "-"
        return "Quiz ${g.rules} watched=${g.usage.watchedMs / 60_000} min left=$left due=${g.due} " +
            "showing=${g.showing} ticking=$ticking home=$homeInFront panel=$panelOpen"
    }
}

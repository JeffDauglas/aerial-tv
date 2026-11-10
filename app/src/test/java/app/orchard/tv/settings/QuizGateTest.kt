package app.orchard.tv.settings

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30])
class QuizGateTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        QuizGate::class.java.getDeclaredField("started").apply { isAccessible = true }.setBoolean(null, false)
        app.getSharedPreferences("quiz", 0).edit().clear().commit()
        QuizGate.init(app)
        QuizGate.turnOff()
        QuizGate.every(15)
    }

    @Test
    fun freshSettingsAskAfterAFullInterval() {
        assertEquals(0L, QuizGate.askedAt)
        assertFalse(QuizGate.count(14 * 60_000L, 1_000_000L))
        assertTrue(QuizGate.count(60_000L, 1_060_000L))
        assertTrue(QuizGate.showing)
    }

    @Test
    fun rightAnswerClosesAndClearsPendingQuestion() {
        QuizGate.show(1_000_000L)
        assertTrue(QuizGate.answer(QuizGate.question!!.answer, 1_000_001L))
        assertFalse(QuizGate.showing)
        assertFalse(QuizGate.usage.due)
        assertEquals(0L, QuizGate.usage.watchedMs)
    }

    @Test
    fun wrongAnswersNeverReleaseAndRecentQuestionsDoNotRepeat() {
        QuizGate.show(1_000_000L)
        val seen = mutableSetOf(QuizGate.question!!.identity)
        repeat(100) { attempt ->
            val q = QuizGate.question!!
            assertFalse(QuizGate.answer((q.answer + 1) % q.options.size, 1_000_001L + attempt))
            if (attempt < 49) assertTrue(seen.add(QuizGate.question!!.identity))
        }
        assertTrue(QuizGate.showing)
        assertTrue(QuizGate.usage.due)
        assertTrue(QuizGate.answer(QuizGate.question!!.answer, 2_000_000L))
    }

    @Test
    fun recentQuestionsSurviveReinitialization() {
        QuizGate.show(1_000_000L)
        val previous = QuizGate.question!!.identity
        QuizGate.answer(QuizGate.question!!.answer, 1_000_001L)
        QuizGate::class.java.getDeclaredField("started").apply { isAccessible = true }.setBoolean(null, false)
        QuizGate.init(app)
        repeat(30) {
            QuizGate.show(1_100_000L + it)
            assertNotEquals(previous, QuizGate.question!!.identity)
            QuizGate.answer(QuizGate.question!!.answer, 1_100_001L + it)
        }
    }

    @Test
    fun disablingQuestionsClearsThePendingGate() {
        QuizGate.show(1_000_000L)
        QuizGate.turnOff()
        assertFalse(QuizGate.showing)
        assertFalse(QuizGate.usage.due)
        assertFalse(QuizGate.count(60 * 60_000L, 5_000_000L))
    }
}

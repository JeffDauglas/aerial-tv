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
    fun thirdWrongAnswerClosesButEarlierOnesDoNot() {
        QuizGate.show(1_000_000L)
        repeat(3) { attempt ->
            val q = QuizGate.question!!
            assertEquals(attempt == 2, QuizGate.answer((q.answer + 1) % q.options.size, 1_000_001L + attempt))
        }
        assertFalse(QuizGate.showing)
        assertFalse(QuizGate.usage.due)
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

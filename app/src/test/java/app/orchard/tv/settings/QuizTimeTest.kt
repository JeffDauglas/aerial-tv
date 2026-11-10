package app.orchard.tv.settings

import app.orchard.tv.settings.QuizKind.Both
import app.orchard.tv.settings.QuizKind.English
import app.orchard.tv.settings.QuizKind.Math
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The questions as a grade 2 child gets them: the arithmetic is right, the answer is always one of
 * the options and near it, and the English asks for the word that is in the picture.
 */
class QuizTimeTest {
    @Test
    fun firstQuestionDoesNotRequireAPreviousQuestion() {
        assertTrue(QuizTime.isDue(60_000, 60_000, 0, 60_000))
        assertTrue(!QuizTime.isDue(59_999, 60_000, 0, 60_000))
    }

    @Test
    fun laterQuestionsRequireBothWatchingAndMinimumGap() {
        val asked = 1_000_000L
        assertTrue(!QuizTime.isDue(60_000, 60_000, asked, asked + 60_000))
        assertTrue(QuizTime.isDue(60_000, 60_000, asked, asked + QuizGate.MIN_GAP_MS))
        assertTrue(!QuizTime.isDue(59_999, 60_000, asked, asked + QuizGate.MIN_GAP_MS))
        assertTrue(!QuizTime.isDue(60_000, 60_000, asked, asked - 1))
    }

    private val random = Random(7)

    /** What a sum says: "27 + 8 = ?" -> 35, "9 × 4 = ?" -> 36, "52 − 7 = ?" -> 45. */
    private fun answerOf(prompt: String): Int {
        val (a, op, b) = Regex("""^(\d+) ([+−×]) (\d+) = \?$""").find(prompt)!!
            .destructured.let { Triple(it.component1().toInt(), it.component2(), it.component3().toInt()) }
        return when (op) {
            "+" -> a + b
            "−" -> a - b
            else -> a * b
        }
    }

    @Test
    fun mathQuestionsAgreeWithTheirOwnSum() {
        repeat(500) {
            val q = QuizTime.math(random)
            val answer = answerOf(q.prompt)
            assertEquals("${q.prompt} should be $answer", answer.toString(), q.right)
            assertTrue("${q.prompt} gives $answer, not a number from 0 to 100", answer in 0..100)
            assertNull("${q.prompt} is arithmetic, which asks what it says", q.ask)
        }
    }

    @Test
    fun mathOptionsAreNearTheAnswerAndDistinct() {
        repeat(500) {
            val q = QuizTime.math(random)
            assertEquals("${q.prompt} should offer four answers", 4, q.options.size)
            assertEquals("${q.prompt} repeats an answer: ${q.options}", q.options.size, q.options.distinct().size)
            val answer = q.right.toInt()
            q.options.forEach { o ->
                val n = o.toIntOrNull()
                assertNotNull("${q.prompt} offers \"$o\", which is not a number", n)
                // Close enough to pick out by working it out, not so far that it is a guess.
                assertTrue("${q.prompt} offers $n, nowhere near $answer", abs(n!! - answer) <= 30)
                assertTrue("${q.prompt} offers $n below zero", n >= 0)
            }
        }
    }

    @Test
    fun mathCoversTheTableAndTwoDigitSums() {
        val prompts = (1..800).map { QuizTime.math(random).prompt }
        assertTrue("no times table among $prompts", prompts.any { "×" in it })
        assertTrue("no taking away among $prompts", prompts.any { "−" in it })
        assertTrue("no adding among $prompts", prompts.any { "+" in it })
    }

    @Test
    fun englishQuestionsOfferTheirOwnAnswer() {
        repeat(500) {
            val q = QuizTime.english(random)
            assertTrue("an English question has a picture on it", !q.subject.isNullOrBlank())
            assertEquals("an English question stands on the picture", "", q.prompt)
            assertEquals("${q.subject} should offer three answers", 3, q.options.size)
            assertEquals("${q.subject} repeats an answer: ${q.options}", q.options.size, q.options.distinct().size)
            assertTrue("${q.subject} answers ${q.right}, which it does not offer", q.answer in q.options.indices)
            when (q.ask) {
                // The word for the picture is a word, not a number.
                QuizAsk.Thing -> assertTrue("${q.subject} answers ${q.right} with a number", q.right.toIntOrNull() == null)
                // How many there are is spelt out, as grade 2 English spells it.
                QuizAsk.Count -> q.options.forEach {
                    assertTrue("${q.subject} offers \"$it\", a number rather than its word", it.toIntOrNull() == null)
                }
                else -> throw AssertionError("an English question should ask for something: ${q.subject}")
            }
        }
    }

    @Test
    fun bothKindsComeUp() {
        val questions = (1..200).map { QuizTime.question(Both, random) }
        val english = questions.count { it.ask != null }
        assertTrue("asked no English in 200 questions", english > 40)
        assertTrue("asked no sums in 200 questions", questions.size - english > 40)
        // And English asks both ways, in the same run as a child would get them.
        val asks = (1..300).mapNotNull { QuizTime.question(English, random).ask }.toSet()
        assertEquals("English asks both for a word and for a count", QuizAsk.entries.toSet(), asks)
    }

    @Test
    fun onlyTheRightAnswerIsRight() {
        repeat(200) {
            val q = QuizTime.question(Both, random)
            q.options.indices.forEach { i ->
                assertEquals("${q.prompt}${q.ask ?: ""} picked ${q.options[i]}", i == q.answer, QuizTime.check(q, i))
            }
        }
    }

    @Test
    fun rulesHoldWhatTheSettingsOffer() {
        // The default: a question every quarter of an hour, one right to go on, three tries.
        val rules = QuizRules()
        assertTrue("$rules", rules.valid)
        assertTrue("$rules", rules.on)
        assertEquals(QuizGate.DEFAULT_EVERY, rules.everyMinutes)
        // Off is everyMinutes = 0, which the settings offer.
        assertTrue("questions are off at 0", !QuizRules(everyMinutes = 0).on)
        assertTrue(QuizRules(everyMinutes = 0).valid)
        // Nothing beyond what the settings and the box can use.
        assertTrue(!QuizRules(everyMinutes = QuizGate.MAX_EVERY + 1).valid)
        assertTrue(!QuizRules(everyMinutes = -1).valid)
        // The kinds are read back from what is stored, and an unknown one is both.
        QuizKind.entries.forEach { assertEquals(it, QuizKind.of(it.key)) }
        assertEquals(Both, QuizKind.of(null))
        assertEquals(Both, QuizKind.of("sums"))
        // The settings ask for each kind on its own.
        assertNull(QuizTime.question(Math, random).ask)
        assertNotNull(QuizTime.question(English, random).ask)
    }
}

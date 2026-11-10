package app.orchard.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.settings.QuizAsk
import app.orchard.tv.settings.QuizGate
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/**
 * The question between programmes (QuizGate): a grade 2 sum or a word, over everything. Nothing
 * behind it is reachable; Back and the remote's Menu do nothing, since the box is answered, not
 * dismissed. Only a correct answer closes it. HomeScreen pauses its wallpapers while this box is visible; onDone also
 * asks QuizWatch to return to an interrupted app, whose playback follows its own lifecycle.
 *
 * [onDone] runs once it is answered: Home closes the overlay and the count starts again.
 */
@Composable
fun QuizBox(visible: Boolean, onDone: () -> Unit) {
    // The question last drawn: while the box fades out it is still there, and a wrong answer's
    // replacement comes in place of it without the panel blinking.
    val question = QuizGate.question
    var shown by remember { mutableStateOf(question) }
    if (visible && question != null) shown = question
    // Counted by QuizGate, so that a wrong answer's replacement says the same as the box did.
    val wrong = QuizGate.wrong

    BackHandler(enabled = visible) { /* answered, not dismissed */ }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.96f),
        exit = fadeOut(tween(200, easing = Motion.EaseOut)) + scaleOut(tween(200, easing = Motion.EaseOut), targetScale = 0.98f),
        modifier = Modifier.fillMaxSize(),
    ) {
        val q = shown ?: return@AnimatedVisibility
        val first = remember { FocusRequester() }
        // Each question opens on its first answer, as the panel comes in.
        LaunchedEffect(q) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        Box(
            Modifier.fillMaxSize().consumeTaps().background(Color.Black.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .liquidGlass(30.dp)
                    .padding(horizontal = 44.dp, vertical = 32.dp)
                    // The number keys answer where the remote has them: 1 to 4, left to right, as
                    // the key's own number is written under each answer.
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val code = e.nativeKeyEvent.keyCode
                        when (code) {
                            in NativeKeyEvent.KEYCODE_1..NativeKeyEvent.KEYCODE_4 -> {
                                val i = code - NativeKeyEvent.KEYCODE_1
                                if (i < q.options.size) answer(i, first, onDone)
                                true
                            }
                            NativeKeyEvent.KEYCODE_MENU -> true
                            else -> false
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // A wrong answer turns the sign, as the line under the question changes too.
                    GlyphIcon(if (wrong == 0) Glyph.Kids else Glyph.Sun, if (wrong == 0) Palette.Label else Palette.Orange, 22.dp)
                    BasicText(
                        stringResource(R.string.quiz_title, QuizGate.rules.everyMinutes),
                        style = Type.Callout.copy(color = Palette.SecondaryLabel),
                    )
                }
                Spacer(Modifier.height(18.dp))
                // What the question is about, large: a picture, or a row of them.
                q.subject?.let {
                    BasicText(
                        it,
                        style = Type.LargeTitle.copy(fontSize = if (it.length > 24) 34.sp else 60.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                BasicText(
                    // English asks in the viewer's language; a sum is the question itself.
                    when (q.ask) {
                        QuizAsk.Thing -> stringResource(R.string.quiz_what_in_english)
                        QuizAsk.Count -> stringResource(R.string.quiz_how_many)
                        null -> q.prompt
                    },
                    // A sum is the question; "What is this in English?" is its label, under the picture.
                    style = if (q.subject == null) Type.LargeTitle.copy(fontSize = 40.sp) else Type.Title,
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = when {
                        wrong > 0 -> stringResource(R.string.quiz_wrong)
                        wrong == 0 -> stringResource(R.string.quiz_hint)
                        else -> stringResource(R.string.quiz_hint)
                    },
                    style = Type.Caption.copy(
                        color = if (wrong > 0) Palette.Orange else Palette.TertiaryLabel,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(26.dp))
                // A row of short answers, two by two where a word is long.
                val wide = q.options.any { it.length > 4 }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    q.options.chunked(if (wide) 2 else 4).forEachIndexed { row, items ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items.forEachIndexed { col, option ->
                                val i = row * (if (wide) 2 else 4) + col
                                QuizOption(option, i, if (i == 0) first else null) {
                                    answer(i, first, onDone)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The answer at [i] picked: only a correct answer runs [onDone]. A wrong answer leaves the box
 * up with a fresh question, and focus back on the first answer.
 */
private fun answer(i: Int, first: FocusRequester, onDone: () -> Unit) {
    val finished = QuizGate.answer(i, System.currentTimeMillis())
    if (finished) {
        onDone()
        return
    }
    // The next question takes the focus where the box opens on it.
    runCatching { first.requestFocus() }
}

@Composable
private fun QuizOption(label: String, index: Int, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill,
            tween(140, easing = Motion.EaseOut), label = "quiz-option",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier
                .smoothBackground({ bg }, 16.dp)
                .height(64.dp)
                .widthIn(min = 120.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(label, style = Type.Title.copy(color = fg), maxLines = 1)
            // The key that answers it, where the remote has number keys.
            BasicText(
                (index + 1).toString(),
                style = Type.Caption.copy(color = if (state.focused) Palette.OnFocus.copy(alpha = 0.55f) else Palette.TertiaryLabel),
            )
        }
    }
}

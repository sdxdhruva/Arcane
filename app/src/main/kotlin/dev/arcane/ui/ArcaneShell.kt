package dev.arcane.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arcane.data.AppGraph
import dev.arcane.ui.simple.SimpleBrowser
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.arcaneBackdrop
import dev.arcane.ui.unlock.UnlockScreen
import dev.arcane.ui.vault.VaultBrowser
import dev.arcane.ui.viewer.VaultViewer

/** How the current screen was reached, which is the only thing that decides what is on it. */
private enum class Entry { GATE, VAULT, PLAIN }

/**
 * The whole app is this: a door, and one of two things behind it.
 *
 * The door is the entire locked screen. Nothing else is drawn — no tab, no icon, no word like "vault" — because
 * an affordance that says *there is somewhere else to go* is the tell the design exists to avoid. Behind it
 * there is exactly one way in and one way out, so there is nothing to switch between: the passphrase opens the
 * encrypted folder, and the wrong number of wrong passphrases opens plain browsing of the phone's own files.
 * A person who has been handed the second one has been handed a screen with no secret in it, and no strip at
 * the bottom advertising that a first one exists.
 *
 * Opening and closing use a fade rather than a slide so the header row never appears to move: a person watching
 * over a shoulder sees one screen become another, not a door being opened.
 */
@Composable
fun ArcaneShell(graph: AppGraph) {
    val opened by graph.holder.opened.collectAsStateWithLifecycle()
    val plain by graph.plain.collectAsStateWithLifecycle()
    val viewing by graph.viewing.collectAsStateWithLifecycle()
    val notice = rememberNotice(graph)
    val entry = when {
        opened != null -> Entry.VAULT
        plain -> Entry.PLAIN
        else -> Entry.GATE
    }
    // Edge-to-edge with both system bars hidden: the backdrop runs under everything, but no control may sit
    // under the punch-hole camera, a transient bar, or the keyboard. `safeDrawing` is the one inset set that
    // covers all three, and because the bars are hidden rather than merely transparent it is zero until one
    // of them is actually needed — so nothing jumps when a swipe brings them back.
    val rise = if (ArcaneMotion.durationScale() == 0f) 0 else with(LocalDensity.current) { 24.dp.toPx().toInt() }

    // The viewer closes before anything else does, because a back gesture that silently leaves a video on
    // screen while the phone goes back to its door is the one thing nobody meant to happen.
    BackHandler(enabled = viewing != null) {
        graph.session.touch()
        graph.view(null)
    }
    // Out of plain browsing there is only the door. That is deliberate: it is the way an owner gets back to
    // their passphrase, and the way anyone else gets out of a screen that has nothing in it.
    BackHandler(enabled = entry == Entry.PLAIN) {
        graph.leavePlain()
    }

    Box(Modifier.fillMaxSize().arcaneBackdrop()) {
        AnimatedContent(
            targetState = entry,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            transitionSpec = {
                fadeIn(tween(ArcaneMotion.quick)) + slideInVertically(tween(ArcaneMotion.slow)) { rise } togetherWith
                    fadeOut(tween(ArcaneMotion.quick)) + slideOutVertically(tween(ArcaneMotion.quick)) { -rise }
            },
            label = "entry",
        ) { target ->
            when (target) {
                Entry.GATE -> UnlockScreen(graph)

                // A notice belongs to the realm that just refused something. Plain browsing has no realm, so it
                // gets no sentence about one: "The vault locked." on a screen of ordinary photos is a tell. The
                // browser takes the weight so the banner it sits above is actually on screen.
                Entry.VAULT -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { VaultBrowser(graph = graph) }
                    notice.value?.let { message ->
                        NoticeBanner(
                            message = message,
                            onDismiss = { notice.dismiss() },
                            modifier = Modifier.padding(top = ArcaneSpace.xs, bottom = ArcaneSpace.sm),
                        )
                    }
                }

                Entry.PLAIN -> SimpleBrowser(graph = graph)
            }
        }

        viewing?.let { record ->
            VaultViewer(graph = graph, record = record, onBack = { graph.view(null) })
        }
    }
}

/**
 * A refusal, shown in place instead of as a snackbar.
 *
 * The repository's words come through verbatim: "The vault locked." is a sentence worth reading once and
 * believing, and a stock bar that vanishes on a timer is not how this app reports something that just
 * happened to its keys.
 */
@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(ArcaneRadius.field)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, ArcanePalette.EdgeStrong, shape)
            .semantics { contentDescription = "Notice: $message" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Icon(
            Icons.Rounded.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = ArcanePalette.TextHigh,
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.Close,
            contentDescription = "Dismiss notice",
            tint = ArcanePalette.TextFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The latest refusal, held long enough to read.
 *
 * Auto-dismiss is a plain timeout rather than a duration that scales with the message, because a notice that
 * stays forever hides the row underneath it and one that leaves too fast hides the reason it was there.
 */
@Composable
private fun rememberNotice(graph: AppGraph): HeldNotice {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph) {
        graph.repository.notices.collect { message -> text = message }
    }
    LaunchedEffect(text) {
        if (text != null) {
            kotlinx.coroutines.delay(6_000)
            text = null
        }
    }
    return HeldNotice(text) { text = null }
}

/** A notice and the only two things anyone can do to it. */
class HeldNotice(val value: String?, val dismiss: () -> Unit)

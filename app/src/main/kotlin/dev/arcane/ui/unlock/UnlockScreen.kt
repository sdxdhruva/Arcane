package dev.arcane.ui.unlock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.arcane.R
import dev.arcane.data.AppGraph
import dev.arcane.data.AppPrefs
import dev.arcane.data.SetupOutcome
import dev.arcane.data.SetupReason
import dev.arcane.data.SetupStage
import dev.arcane.data.UnlockOutcome
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneButton
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.theme.ArcaneMotion
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The cryptographic gate.
 *
 * Designed as a clean, atmospheric luxury cipher entry rather than a conventional login form:
 * - Centered celestial brand emblem with ambient breathing aura and keyhole aperture
 * - Floating glass capsule input with password dot masking and glowing focus border
 * - Embedded inline unlock action button that illuminates as keys are typed
 * - Seamless breathing derivation state without bulky progress bars or instructional essays
 * - Preserves zero-knowledge key derivation and failover coercion safety
 */
@Composable
fun UnlockScreen(graph: AppGraph) {
    val prefs by graph.settings.prefs.collectAsState(initial = AppPrefs.EMPTY)
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun typedSubmit() {
        val attempt = passphrase
        if (attempt.isBlank() || busy) return
        busy = true
        error = null
        passphrase = ""
        scope.launch {
            error = when (graph.engine.unlock(attempt)) {
                is UnlockOutcome.Opened -> null
                UnlockOutcome.FailedOver -> null
                is UnlockOutcome.Rejected -> NOT_RECOGNISED
                is UnlockOutcome.Broken -> NOT_RECOGNISED
                UnlockOutcome.NeedsSetup -> null
            }
            busy = false
        }
    }

    // 10-second silent safety mechanism: if correct key not entered within 10s, auto-failover to default system vault.
    // 100% invisible in background: no indicator or countdown is revealed on screen.
    LaunchedEffect(prefs.isSetUp, busy) {
        if (!prefs.isSetUp || busy) return@LaunchedEffect
        var remaining = 10
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            if (!busy) {
                remaining--
            }
        }
        if (!busy) {
            graph.enterPlain()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ArcaneSpace.xl),
        ) {
            ArcaneBrandEmblem(busy = busy)

            if (!prefs.isSetUp) {
                FirstRun(graph = graph)
                return@Column
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
            ) {
                CipherKeyCapsule(
                    value = passphrase,
                    onChange = {
                        passphrase = it
                        if (error != null) error = null
                    },
                    onSubmit = { typedSubmit() },
                    busy = busy,
                    error = error,
                    placeholder = "Enter cipher key…",
                )
            }
        }
    }
}

/** Rejection notification: minimal, concise, identical across real and decoy realms. */
private const val NOT_RECOGNISED = "Cipher key mismatch"

/**
 * The brand emblem of Arcane.
 *
 * Displays the titanium-obsidian cryptographic shield with neon violet aperture key glyph.
 * Pulses gently in resting state, and breathes with vibrant energetic radiance during key derivation.
 */
@Composable
fun ArcaneBrandEmblem(
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    size: Dp = 108.dp,
    showWordmark: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "emblem-pulse")
    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = if (busy) 0.40f else 0.16f,
        targetValue = if (busy) 0.88f else 0.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 750 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aura-alpha",
    )
    val auraScale by infiniteTransition.animateFloat(
        initialValue = if (busy) 1.0f else 0.96f,
        targetValue = if (busy) 1.25f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 750 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aura-scale",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.md),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Ambient violet halo disc
            Box(
                modifier = Modifier
                    .size(size * 1.55f)
                    .graphicsLayer {
                        scaleX = auraScale
                        scaleY = auraScale
                        alpha = auraAlpha
                    }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                ArcanePalette.Violet.copy(alpha = 0.55f),
                                ArcanePalette.VioletDeep.copy(alpha = 0.22f),
                                Color.Transparent,
                            )
                        ),
                        shape = CircleShape,
                    )
            )

            // The cyber shield emblem
            Image(
                painter = painterResource(R.drawable.arcane_shield),
                contentDescription = "Arcane Security Emblem",
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        if (busy) {
                            val pulseScale = 1f + (auraScale - 1f) * 0.15f
                            scaleX = pulseScale
                            scaleY = pulseScale
                        }
                    },
            )
        }

        if (showWordmark) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "A R C A N E",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        color = ArcanePalette.TextHigh,
                    ),
                )
                Text(
                    text = if (busy) "DERIVING SECURE VOLUME…" else "ZERO-KNOWLEDGE ENCRYPTED VAULT",
                    style = TextStyle(
                        fontFamily = ArcaneType.Mono.fontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        color = if (busy) ArcanePalette.Cyan else ArcanePalette.TextFaint,
                    ),
                )
            }
        }
    }
}

/**
 * A sleek floating glass capsule for entering cipher keys.
 *
 * Eliminates traditional web login boxes, heavy borders, and separate submit buttons.
 * Integrates dot masking, an inline visibility toggle, and an illuminated 1-tap submission arrow.
 */
@Composable
fun CipherKeyCapsule(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    busy: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    placeholder: String = "Enter cipher key…",
) {
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(error) {
        if (error != null) {
            for (offset in listOf(-12f, 10f, -8f, 6f, -3f, 0f)) {
                shakeOffset.animateTo(offset, animationSpec = tween(45))
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var revealed by remember { mutableStateOf(false) }

    val capsuleShape = RoundedCornerShape(28.dp)
    val borderColor = when {
        error != null -> ArcanePalette.Rose.copy(alpha = 0.85f)
        isFocused -> ArcanePalette.Violet.copy(alpha = 0.90f)
        value.isNotEmpty() -> ArcanePalette.EdgeStrong
        else -> ArcanePalette.Edge
    }
    val borderWidth = if (isFocused || error != null) 1.5.dp else 1.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(capsuleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ArcanePalette.Ink3.copy(alpha = 0.92f),
                            ArcanePalette.Ink2.copy(alpha = 0.95f),
                        )
                    )
                )
                .border(borderWidth, borderColor, capsuleShape)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left lock status icon
            Icon(
                imageVector = if (busy) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                contentDescription = null,
                tint = when {
                    error != null -> ArcanePalette.Rose
                    busy -> ArcanePalette.Cyan
                    value.isNotEmpty() -> ArcanePalette.Violet
                    else -> ArcanePalette.TextFaint
                },
                modifier = Modifier.size(20.dp),
            )

            // Center area: either deriving indicator or clean monospace input
            if (busy) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ArcanePalette.Cyan,
                        trackColor = ArcanePalette.Ink4,
                    )
                    Text(
                        text = "Decrypting volume…",
                        style = TextStyle(
                            fontFamily = ArcaneType.Mono.fontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                            color = ArcanePalette.Cyan,
                        ),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                fontFamily = ArcaneType.Mono.fontFamily,
                                fontSize = 15.sp,
                                letterSpacing = 1.sp,
                                color = ArcanePalette.TextFaint,
                            ),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        enabled = !busy,
                        singleLine = true,
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        textStyle = TextStyle(
                            fontFamily = ArcaneType.Mono.fontFamily,
                            fontSize = 16.sp,
                            letterSpacing = if (revealed) 1.5.sp else 3.sp,
                            fontWeight = FontWeight.Medium,
                            color = ArcanePalette.TextHigh,
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                        cursorBrush = SolidColor(ArcanePalette.Violet),
                        interactionSource = interactionSource,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Password reveal toggle
                ArcaneAction(
                    icon = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    hint = if (revealed) "Hide key" else "Show key",
                    onClick = { revealed = !revealed },
                    enabled = !busy,
                    tint = ArcanePalette.TextMedium,
                    target = 38.dp,
                )
                Spacer(Modifier.width(4.dp))

                // Integrated 1-tap submit arrow button
                val canSubmit = value.isNotEmpty() && !busy
                val submitInteractions = remember { MutableInteractionSource() }
                val isPressed by submitInteractions.collectIsPressedAsState()
                val submitScale by animateFloatAsState(if (isPressed) 0.92f else 1f, label = "submit-scale")

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = submitScale
                            scaleY = submitScale
                        }
                        .clip(CircleShape)
                        .background(
                            if (canSubmit) {
                                Brush.linearGradient(listOf(ArcanePalette.VioletDeep, ArcanePalette.Violet))
                            } else {
                                SolidColor(ArcanePalette.Ink4.copy(alpha = 0.6f))
                            }
                        )
                        .clickable(
                            enabled = canSubmit,
                            interactionSource = submitInteractions,
                            indication = null,
                            onClick = onSubmit,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "Unlock vault",
                        tint = if (canSubmit) Color.White else ArcanePalette.TextFaint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Clean inline error badge
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(tween(ArcaneMotion.quick)),
            exit = fadeOut(tween(ArcaneMotion.quick)),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ArcanePalette.Rose.copy(alpha = 0.12f))
                    .border(1.dp, ArcanePalette.Rose.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ArcanePalette.Rose),
                )
                Text(
                    text = error ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = ArcanePalette.Rose,
                )
            }
        }
    }
}

/** Setup wizard with matching luxury cryptographic styling. */
@Composable
private fun FirstRun(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var main by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var explained by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<SetupStage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val hasMinLength = main.length >= 8
    val matches = main.isNotEmpty() && confirm == main

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.md),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Initialize Volume",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = ArcanePalette.TextHigh,
            )
            Text(
                "Set your master key. The key material derives the volume cipher and cannot be recovered or reset.",
                style = MaterialTheme.typography.bodySmall,
                color = ArcanePalette.TextMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ArcaneSpace.sm),
            )
        }

        // Master key input capsule
        CipherKeyCapsule(
            value = main,
            onChange = { main = it; error = null },
            onSubmit = {},
            busy = stage != null,
            error = error.takeIf { stage == null },
            placeholder = "Create master key…",
        )

        // Confirmation key capsule
        CipherKeyCapsule(
            value = confirm,
            onChange = { confirm = it; error = null },
            onSubmit = {},
            busy = stage != null,
            error = null,
            placeholder = "Confirm master key…",
        )

        // Subtle security verification indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (hasMinLength) ArcanePalette.Mint else ArcanePalette.Ink4),
                )
                Text(
                    "8+ characters",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasMinLength) ArcanePalette.Mint else ArcanePalette.TextFaint,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (matches) ArcanePalette.Mint else ArcanePalette.Ink4),
                )
                Text(
                    "Keys match",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (matches) ArcanePalette.Mint else ArcanePalette.TextFaint,
                )
            }
        }

        ArcaneButton(
            label = when (stage) {
                null -> "Create Encrypted Vault"
                SetupStage.TUNING -> "Calibrating hardware…"
                SetupStage.CREATING -> "Deriving volume cipher…"
            },
            enabled = stage == null && hasMinLength && matches,
            onClick = {
                scope.launch {
                    stage = SetupStage.TUNING
                    val outcome = graph.engine.setUp(main) { stage = it }
                    stage = null
                    error = when (outcome) {
                        is SetupOutcome.Rejected -> when (outcome.reason) {
                            SetupReason.PASSPHRASE_TOO_SHORT -> "At least 8 characters required."
                            SetupReason.VAULT_ALREADY_EXISTS -> "Vault volume already exists."
                            SetupReason.COULD_NOT_CREATE -> "Unable to create encrypted volume."
                        }
                        is SetupOutcome.Opened -> {
                            main = ""
                            confirm = ""
                            null
                        }
                    }
                }
            },
            icon = Icons.Rounded.Shield,
            modifier = Modifier.fillMaxWidth(),
        )

        ArcaneChip(
            label = if (explained) "Hide security model" else "Stealth security model",
            selected = explained,
            enabled = stage == null,
            onClick = { explained = !explained },
        )

        AnimatedVisibility(
            visible = explained,
            enter = fadeIn(tween(ArcaneMotion.quick)),
            exit = fadeOut(tween(ArcaneMotion.quick)),
        ) {
            Text(
                "Refusing repeated key entries triggers stealth failover: the app quietly opens plain browsing of " +
                    "the phone's standard files with no telltale error. The exact threshold is derived per device so " +
                    "it cannot be predicted. Entering the correct master key always unlocks your true volume.",
                style = MaterialTheme.typography.bodySmall,
                color = ArcanePalette.TextMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ArcaneSpace.sm),
            )
        }
    }
}

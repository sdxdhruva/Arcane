package dev.arcane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType

/**
 * The passphrase field.
 *
 * Not a Material `TextField`, because its label lives inside the box and its supporting text reserves a line
 * even when empty — which makes the unlock screen jump on the first wrong attempt. Two rules here come from
 * the threat model rather than taste: nothing about the field hints which realm a passphrase belongs to, and
 * an error replaces the helper text in place instead of adding a row.
 */
@Composable
fun PassphraseField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    helper: String? = null,
    error: String? = null,
    enabled: Boolean = true,
) {
    var revealed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ArcaneRadius.field)
    val accent = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ArcaneSpace.tight)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (error != null) MaterialTheme.colorScheme.error else ArcanePalette.TextFaint,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, if (error != null) accent else ArcanePalette.EdgeStrong, shape)
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                // Revealing is the user's choice, and the field is never pre-filled — but once they have
                // toggled it, the text they typed is what they should see.
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(
                    fontFamily = ArcaneType.Mono.fontFamily,
                    fontSize = ArcaneType.Mono.fontSize,
                    letterSpacing = ArcaneType.Mono.letterSpacing,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) ArcanePalette.TextHigh else ArcanePalette.TextFaint,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                cursorBrush = SolidColor(accent),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 16.dp),
            )
            ArcaneAction(
                icon = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                hint = if (revealed) "Hide passphrase" else "Show passphrase",
                onClick = { revealed = !revealed },
                enabled = enabled,
                tint = ArcanePalette.TextMedium,
                target = 48.dp,
            )
        }
        Text(
            text = error ?: helper ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else ArcanePalette.TextFaint,
            minLines = 1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // A refusal that only changes a colour is silence to a screen reader; it is also the one message
            // on this screen that has to arrive without the person looking for it.
            modifier = Modifier
                .padding(start = 4.dp)
                .semantics {
                    if (error != null) liveRegion = LiveRegionMode.Assertive
                },
        )
    }
}

/** The small uppercase line above a section, so eyebrows never drift into three different styles. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
    ) {
        Box(
            Modifier
                .size(width = 14.dp, height = 2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        )
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ArcanePalette.TextFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A path crumb. Only the last one is bright, because only the last one is the room you are standing in. */
@Composable
fun Crumb(name: String, current: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ArcaneRadiusSm))
            .background(if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(enabled = !current, interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                current || pressed -> ArcanePalette.TextHigh
                else -> ArcanePalette.TextMedium
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!current) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = ArcanePalette.TextFaint,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

private val ArcaneRadiusSm = 8.dp

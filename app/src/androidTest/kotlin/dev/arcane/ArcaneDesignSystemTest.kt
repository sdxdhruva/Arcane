package dev.arcane

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.components.ArcaneEmptyState
import dev.arcane.ui.components.ArcaneSheet
import dev.arcane.ui.components.Shimmer
import dev.arcane.ui.theme.ArcaneTheme
import org.junit.Rule
import org.junit.Test

/**
 * The design system, exercised as the components the screens are built from.
 *
 * A shell-level test would need a live realm, and a realm needs a passphrase and a file system. What can be
 * asserted honestly here is the four things a premium-feeling UI is made of: a control paints, reports the
 * state it is in to an accessibility service, fires the callback its caller passed, and stays quiet when it
 * has nothing to say. Those are also the four things a refactor breaks silently.
 */
class ArcaneDesignSystemTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aChipReportsItsSelectionAndPassesTheTapThrough() {
        var clicks = 0
        compose.setContent {
            ArcaneTheme {
                ArcaneChip(label = "Monospace", onClick = { clicks++ }, selected = true)
            }
        }

        compose.onNodeWithText("Monospace").assertIsDisplayed().assertIsSelected().performClick()
        check(clicks == 1) { "expected one click, saw $clicks" }
    }

    @Test
    fun anEmptyStateOffersOneActionAndSaysWhy() {
        var tapped = false
        compose.setContent {
            ArcaneTheme {
                ArcaneEmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Nothing in this folder",
                    body = "Import something and it will appear here, encrypted.",
                    actionLabel = "Import",
                    onAction = { tapped = true },
                )
            }
        }

        compose.onNodeWithText("Nothing in this folder").assertIsDisplayed()
        compose.onNodeWithText("Import something and it will appear here, encrypted.").assertIsDisplayed()
        compose.onNodeWithText("Import").performClick()
        check(tapped) { "the empty state's action did not fire" }
    }

    @Test
    fun aSheetShowsItsContentAndDismissesFromTheScrim() {
        var dismissals = 0
        compose.setContent {
            ArcaneTheme {
                Box(Modifier.fillMaxSize()) {
                    ArcaneSheet(visible = true, onDismiss = { dismissals++ }) {
                        Text("Rename")
                    }
                }
            }
        }

        compose.onNodeWithText("Rename").assertIsDisplayed()
        // The card is bottom-aligned, so the top-left corner is scrim — and the scrim is the only thing on
        // this screen that closes it without an action.
        compose.onRoot().performTouchInput { click(Offset(20f, 20f)) }
        check(dismissals == 1) { "a tap on the scrim did not dismiss the sheet ($dismissals)" }
    }

    @Test
    fun aSkeletonPaintsAndAnnouncesNothing() {
        compose.setContent {
            ArcaneTheme { Shimmer(Modifier.fillMaxSize()) }
        }

        compose.onRoot().assertIsDisplayed()
        // A loading placeholder that TalkBack reads out describes files that do not exist yet, so the tree is
        // expected to carry neither text nor a description.
        val config = compose.onRoot().fetchSemanticsNode().config
        check(!config.contains(SemanticsProperties.Text)) { "a skeleton announced text" }
        check(!config.contains(SemanticsProperties.ContentDescription)) { "a skeleton announced a description" }
    }
}

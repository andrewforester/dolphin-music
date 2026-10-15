package com.voiceproducer.feature.access.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.PillShape
import com.voiceproducer.core.designsystem.mono
import com.voiceproducer.core.designsystem.sans
import com.voiceproducer.feature.access.AccessIntent
import com.voiceproducer.feature.access.AccessUiState
import kotlin.math.roundToInt

const val SELF_HOSTING_URL = "https://github.com/andrewforester/dolphin-music/blob/main/docs/self-hosting.md"

private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
private val FieldShape = RoundedCornerShape(18.dp)
private val DismissDrag = 96.dp

@Composable
fun AccessSheet(
    state: AccessUiState,
    onIntent: (AccessIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenGuide: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val openGuide = onOpenGuide ?: { uriHandler.openUri(SELF_HOSTING_URL) }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(state.sheetOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onIntent(AccessIntent.Dismiss) }
                    .testTag("access-scrim"),
            )
        }
        AnimatedVisibility(
            state.sheetOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SheetBody(state, onIntent, openGuide)
        }
    }
}

@Composable
private fun SheetBody(
    state: AccessUiState,
    onIntent: (AccessIntent) -> Unit,
    openGuide: () -> Unit,
) {
    val dismissPx = with(LocalDensity.current) { DismissDrag.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .offset { IntOffset(0, drag.roundToInt()) }
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Palette.Cream, 1f to Palette.Bubble), SheetShape)
            .border(1.dp, Palette.SheetEdge, SheetShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .draggable(
                rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) },
                Orientation.Vertical,
                onDragStopped = {
                    if (drag > dismissPx) onIntent(AccessIntent.Dismiss)
                    drag = 0f
                },
            ).navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 28.dp)
            .testTag("access-sheet"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 40.dp, height = 4.dp).background(Palette.InkHairline, PillShape))
        Spacer(Modifier.height(22.dp))
        Text("Enter your access code", style = sans(24.sp, 700, Palette.Ink))
        Spacer(Modifier.height(8.dp))
        Text(
            "The composer needs it to make music.",
            style = sans(16.sp, color = Palette.InkMuted),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        CodeField(state, onIntent)
        Box(Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp, start = 6.dp)) {
            state.error?.let {
                Text(it, style = sans(15.sp, 600, Palette.ErrorOnLight), modifier = Modifier.testTag("access-error"))
            }
        }
        ContinueButton(state, onIntent)
        Spacer(Modifier.height(22.dp))
        Text(
            "Run your own backend →",
            style = mono(12.sp, 500, Palette.InkMuted),
            modifier =
                Modifier
                    .clickable(role = Role.Button, onClick = openGuide)
                    .padding(8.dp)
                    .testTag("access-guide"),
        )
    }
}

@Composable
private fun CodeField(
    state: AccessUiState,
    onIntent: (AccessIntent) -> Unit,
) {
    val border = if (state.error != null) Palette.ErrorOnLight else Palette.InkHairline
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White, FieldShape)
            .border(1.dp, border, FieldShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        if (state.code.isEmpty()) {
            Text("word-word-word-word", style = mono(17.sp, 500, Palette.InkMuted.copy(alpha = 0.5f)))
        }
        BasicTextField(
            value = state.code,
            onValueChange = { onIntent(AccessIntent.CodeChanged(it)) },
            singleLine = true,
            enabled = !state.checking,
            textStyle = mono(17.sp, 500, Palette.Ink),
            cursorBrush = SolidColor(Palette.Ink),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { if (state.code.isNotBlank()) onIntent(AccessIntent.Submit) }),
            modifier = Modifier.fillMaxWidth().testTag("access-code"),
        )
    }
}

@Composable
private fun ContinueButton(
    state: AccessUiState,
    onIntent: (AccessIntent) -> Unit,
) {
    val enabled = state.code.isNotBlank() && !state.checking
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (enabled || state.checking) Palette.Ink else Palette.InkHairline, PillShape)
            .clickable(enabled = enabled, role = Role.Button) { onIntent(AccessIntent.Submit) }
            .testTag("access-continue"),
        contentAlignment = Alignment.Center,
    ) {
        if (state.checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Cream, strokeWidth = 2.dp)
                Text("Checking…", style = sans(17.sp, 600, Palette.Cream))
            }
        } else {
            Text("Continue", style = sans(17.sp, 600, if (enabled) Palette.Cream else Palette.InkMuted))
        }
    }
}

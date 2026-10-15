package com.voiceproducer.feature.producer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.PillShape
import com.voiceproducer.core.designsystem.glass
import com.voiceproducer.core.designsystem.mono
import com.voiceproducer.core.designsystem.sans
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.ProducerIntent
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.idleMicLabel
import com.voiceproducer.feature.producer.orb.HEARING_LEVEL

internal enum class MicMode { Off, Connecting, Live, Muted }

internal data class MicLook(
    val mode: MicMode,
    val label: String,
    val hint: String?,
    val dot: Color,
)

internal fun micLook(state: ProducerUiState): MicLook {
    val activity = state.agentActivity
    return when {
        activity == AgentActivity.Idle -> MicLook(MicMode.Off, idleMicLabel(state.transcript), null, Palette.DotIdle)
        activity == AgentActivity.Connecting -> MicLook(MicMode.Connecting, "Connecting…", null, Palette.Cream)
        state.agent.micMuted -> MicLook(MicMode.Muted, "Muted · Composer can't hear you", "Tap to end", Palette.DotIdle)
        activity == AgentActivity.Speaking -> MicLook(MicMode.Live, "Live · Composer speaking", "Tap to end", Palette.Teal)
        activity == AgentActivity.Thinking -> MicLook(MicMode.Live, "Live · Thinking", "Tap to end", Palette.Amber)
        state.userVoiceLevel > HEARING_LEVEL -> MicLook(MicMode.Live, "Live · Hearing you", "Tap to end", Palette.Amber)
        else -> MicLook(MicMode.Live, "Live · Listening", "Tap to end", Palette.Amber)
    }
}

fun asksForMicFirst(
    intent: ProducerIntent,
    state: ProducerUiState,
    hasPermission: Boolean,
): Boolean = intent == ProducerIntent.ToggleAgent && !hasPermission && state.agentActivity == AgentActivity.Idle

internal fun orbDescription(mode: MicMode): String =
    when (mode) {
        MicMode.Off -> "Talk to composer"
        MicMode.Connecting -> "Cancel connecting"
        MicMode.Live, MicMode.Muted -> "Stop session"
    }

@Composable
internal fun OrbHome(
    state: ProducerUiState,
    onPlaced: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = micLook(state)
    Column(
        modifier.height(OrbHomeHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(OrbHomeSize)
                .onGloballyPositioned { onPlaced(it.boundsInRoot().center) }
                .testTag("orb-home"),
        )
        Row(Modifier.height(StatusLineHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 7.dp).size(6.dp).background(look.dot, CircleShape))
            Text(look.label, style = mono(11.sp, 500), maxLines = 1, modifier = Modifier.testTag("mic-label"))
            look.hint?.let {
                Text(" · ", style = mono(11.sp, color = Palette.Muted), maxLines = 1)
                Text(it, style = mono(11.sp, color = Palette.Muted), maxLines = 1, modifier = Modifier.testTag("mic-hint"))
            }
        }
    }
}

private val OrbHomeSize = 96.dp
private val StatusLineHeight = 16.dp
private val OrbHomeHeight = OrbHomeSize + 6.dp + StatusLineHeight

@Composable
internal fun TextInputRow(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }
    Row(
        modifier.fillMaxWidth().glass(PillShape, Palette.GlassBorderStrong).padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("Type to the composer…", style = sans(16.sp, 400, Palette.Muted))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = sans(16.sp, 400),
                cursorBrush = SolidColor(Palette.Cream),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().testTag("text-input"),
            )
        }
        val enabled = text.isNotBlank()
        Text(
            "Send",
            style = sans(14.sp, 700, Palette.Ink),
            modifier =
                Modifier
                    .clip(PillShape)
                    .background(if (enabled) Palette.CreamButton else Palette.CreamButton.copy(alpha = 0.4f))
                    .clickable(enabled = enabled, role = Role.Button) { send() }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    .testTag("send-button"),
        )
    }
}

@Composable
internal fun ErrorLine(
    message: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Text(
        message,
        style = mono(11.sp, color = Palette.Error),
        modifier = modifier.glass(PillShape).padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag),
    )
}

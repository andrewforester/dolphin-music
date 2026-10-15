package com.voiceproducer.feature.producer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.PillShape
import com.voiceproducer.core.designsystem.glass
import com.voiceproducer.core.designsystem.mono
import com.voiceproducer.core.designsystem.sans
import com.voiceproducer.core.model.ApiLogEntry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch

private val RowShape = RoundedCornerShape(12.dp)

private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
private const val FIRST_SUCCESS_STATUS = 200
private const val LAST_SUCCESS_STATUS = 299

fun utcTime(ms: Long): String {
    val s = (ms / 1000).mod(86_400L)

    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${two(s / SECONDS_PER_HOUR)}:${two(s % SECONDS_PER_HOUR / SECONDS_PER_MINUTE)}:${two(s % SECONDS_PER_MINUTE)}"
}

@Composable
internal fun ApiLogPanel(
    entries: ImmutableList<ApiLogEntry>,
    formatTime: (Long) -> String,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink.copy(alpha = 0.94f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .padding(16.dp)
            .testTag("api-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Music API log", style = sans(16.sp, 700, Palette.Cream), modifier = Modifier.weight(1f))
            Text(
                "Close",
                style = sans(12.5.sp, 700, Palette.Cream),
                modifier =
                    Modifier
                        .clip(PillShape)
                        .background(Palette.Glass)
                        .border(1.dp, Palette.Cream.copy(alpha = 0.45f), PillShape)
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .testTag("api-close"),
            )
        }
        if (entries.isEmpty()) {
            Text("No calls yet", style = sans(13.sp, 400, Palette.Muted), modifier = Modifier.testTag("api-empty"))
        }
        LazyColumn(Modifier.weight(1f).testTag("api-list"), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { ApiLogRow(it, formatTime) }
        }
    }
}

@Composable
private fun ApiLogRow(
    entry: ApiLogEntry,
    formatTime: (Long) -> String,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().glass(RowShape, Palette.GlassBorderStrong)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("api-entry-${entry.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(formatTime(entry.timeMs), style = mono(11.sp, 400, Palette.Muted))
            Text(entry.endpoint, style = mono(12.sp, 500, Palette.Cream), modifier = Modifier.weight(1f))
            val (label, color) = statusLabel(entry)
            Text(label, style = mono(12.sp, 500, color), modifier = Modifier.testTag("api-status-${entry.id}"))
            entry.durationMs?.let { Text("${it}ms", style = mono(11.sp, 400, Palette.Muted)) }
            entry.songId?.let { Text(it.take(8), style = mono(11.sp, 400, Palette.Muted)) }
        }
        if (expanded) {
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Block("Request", entry.request, "api-request-${entry.id}")
                val result = listOfNotNull(entry.response, entry.error?.let { "Error: $it" }).joinToString("\n")
                Block(if (entry.response != null) "Response" else "Result", result.ifEmpty { "(in flight)" }, "api-response-${entry.id}")
                Text(
                    "Copy",
                    style = sans(12.5.sp, 700, Palette.Cream),
                    modifier =
                        Modifier
                            .clip(PillShape)
                            .background(Palette.Glass)
                            .border(1.dp, Palette.Cream.copy(alpha = 0.45f), PillShape)
                            .clickable(role = Role.Button) { scope.launch { clipboard.setClipEntry(plainTextClipEntry(entry.copyText())) } }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .testTag("api-copy-${entry.id}"),
                )
            }
        }
    }
}

@Composable
private fun Block(
    title: String,
    text: String,
    tag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = mono(10.sp, 500, Palette.Muted))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.ToolBg)) {
            SelectionContainer {
                Text(
                    text,
                    softWrap = false,
                    style = mono(11.sp, 400, Palette.ToolText),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp).testTag(tag),
                )
            }
        }
    }
}

private fun statusLabel(e: ApiLogEntry): Pair<String, Color> =
    when {
        e.status == null && e.error == null -> "…" to Palette.Ochre
        e.status == null -> "Error" to Palette.Error
        e.status in FIRST_SUCCESS_STATUS..LAST_SUCCESS_STATUS && e.error == null -> "${e.status}" to Palette.Teal
        else -> "${e.status}" to Palette.Error
    }

internal fun ApiLogEntry.copyText(): String =
    buildString {
        append("$endpoint ${status ?: "-"} ${durationMs ?: "-"}ms")
        songId?.let { append(" song-id=$it") }
        append("\nRequest:\n").append(request)
        response?.let { append("\nResponse:\n").append(it) }
        error?.let { append("\nError: ").append(it) }
    }

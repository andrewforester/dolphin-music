package com.voiceproducer.feature.producer.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText("plain text", text))

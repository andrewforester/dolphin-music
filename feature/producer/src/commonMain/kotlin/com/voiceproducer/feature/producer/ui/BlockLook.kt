package com.voiceproducer.feature.producer.ui

import androidx.compose.ui.graphics.Color
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.core.designsystem.formatMs
import com.voiceproducer.feature.producer.SectionKind
import com.voiceproducer.feature.producer.SectionStatus
import com.voiceproducer.feature.producer.SectionUi

internal data class BlockLook(
    val background: Color,
    val content: Color,
    val subLabel: String,
    val pulses: Boolean,
    val failed: Boolean,
)

internal fun blockLook(
    section: SectionUi,
    composing: Boolean,
): BlockLook {
    val edited = section.version > 1
    val content = if (!edited && section.kind == SectionKind.Chorus) Palette.Cream else Palette.Ink
    return BlockLook(
        background = blockBackground(section, edited),
        content = content,
        subLabel = blockSubLabel(section, composing, edited),
        pulses = composing,
        failed = section.status == SectionStatus.Failed,
    )
}

private fun blockBackground(
    section: SectionUi,
    edited: Boolean,
): Color =
    when {
        edited -> if (section.kind == SectionKind.Chorus) Palette.Amber else Palette.Ochre
        section.kind == SectionKind.Intro || section.kind == SectionKind.Outro -> Palette.IntroOutro
        section.kind == SectionKind.Chorus -> Palette.Chorus
        else -> Palette.Verse
    }

private fun blockSubLabel(
    section: SectionUi,
    composing: Boolean,
    edited: Boolean,
): String =
    when {
        composing -> "composing…"
        section.status == SectionStatus.Regenerating -> "regenerating…"
        section.status == SectionStatus.Queued -> "v${section.version + 1} queued"
        section.status == SectionStatus.Failed -> "failed"
        edited -> listOfNotNull("v${section.version}", section.lastTag).joinToString(" · ")
        else -> formatMs(section.endMs - section.startMs)
    }

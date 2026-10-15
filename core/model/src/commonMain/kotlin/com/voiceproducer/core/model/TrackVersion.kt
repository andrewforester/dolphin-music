package com.voiceproducer.core.model

data class TrackVersion(
    val trackId: Long,
    val versionId: Long,
    val number: Int,
    val parentVersionId: Long?,
    val plan: CompositionPlan,
    val sourceMap: SourceMap,
    val audioPath: String,
    val editedSection: String?,
    val diffRangesMs: List<LongRange>,
)

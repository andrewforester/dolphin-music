package com.voiceproducer.core.domain.plan

object StyleConflicts {
    enum class Gender { Female, Male }

    data class Resolution(
        val moved: List<String>,
        val negatives: List<String>,
    )

    private fun words(vararg alternatives: String) =
        Regex("""(?<![\p{L}\p{N}])(?:${alternatives.joinToString("|")})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

    private val femaleWords = words("female", "woman", "women", "girl", "girls")
    private val maleWords = words("male", "man", "men", "boy", "boys")
    private val instrumentalWords = words("instrumental", "no vocals")
    private val vocalWords = words("vocal", "vocals", "singer", "singing", "sung", "choir")
    private val noVocals = words("no vocals")

    private val voiceTypeTable: List<Triple<String, Regex, Gender?>> =
        listOf(
            Triple("mezzo", words("mezzo-soprano", "mezzo"), Gender.Female),
            Triple("soprano", Regex("""(?<![\p{L}\p{N}-])soprano(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE), Gender.Female),
            Triple("contralto", words("contralto"), Gender.Female),
            Triple("alto", words("alto"), Gender.Female),
            Triple("tenor", words("tenor"), null),
            Triple("baritone", words("baritone"), Gender.Male),
            Triple("bass", words("bass vocal", "bass vocals", "basso"), Gender.Male),
        )

    private val voiceContextWords = words("vocal", "vocals", "voice", "singer", "singing", "sung", "choir")
    private val instrumentWords =
        words(
            "sax",
            "saxophone",
            "horn",
            "horns",
            "ukulele",
            "recorder",
            "clarinet",
            "flute",
            "guitar",
            "trumpet",
            "trombone",
            "oboe",
            "bassoon",
            "violin",
            "viola",
            "cello",
            "synth",
            "piano",
            "organ",
            "strings",
        )

    private fun isVoiceContext(style: String): Boolean =
        voiceContextWords.containsMatchIn(style) ||
            (
                !instrumentWords.containsMatchIn(style) &&
                    (femaleWords.containsMatchIn(style) || maleWords.containsMatchIn(style) || style.trim().none { it.isWhitespace() })
            )

    fun voiceTypes(style: String): Set<String> {
        if (!isVoiceContext(style)) return emptySet()
        return voiceTypeTable.filter { (_, re, _) -> re.containsMatchIn(style) }.mapTo(mutableSetOf()) { it.first }
    }

    fun gender(style: String): Gender? {
        val genders = mutableSetOf<Gender>()
        if (femaleWords.containsMatchIn(style)) genders += Gender.Female
        if (maleWords.containsMatchIn(style)) genders += Gender.Male
        if (isVoiceContext(style)) {
            voiceTypeTable.forEach { (_, re, implied) -> if (implied != null && re.containsMatchIn(style)) genders += implied }
        }
        return genders.singleOrNull()
    }

    fun isInstrumental(style: String) = instrumentalWords.containsMatchIn(style)

    fun isVocal(style: String) = vocalWords.containsMatchIn(style) && !noVocals.containsMatchIn(style)

    fun resolve(
        added: List<String>,
        existing: List<String>,
    ): Resolution {
        val moved = mutableListOf<String>()
        val negatives = mutableListOf<String>()

        val genderRule = added.mapNotNull(::gender).toSet().size <= 1
        for (a in added) {
            moveGenderConflicts(a, existing, genderRule, moved, negatives)
            val types = voiceTypes(a)
            if (types.isNotEmpty()) {
                existing.filter { e -> voiceTypes(e).let { it.isNotEmpty() && it.none { t -> t in types } } }.forEach { move(moved, it) }
            }
            moveVocalConflicts(a, existing, moved, negatives)
        }
        return Resolution(moved, negatives.distinctBy { it.lowercase() })
    }

    private fun move(
        moved: MutableList<String>,
        style: String,
    ) {
        if (moved.none { it.equals(style, ignoreCase = true) }) moved += style
    }

    private fun moveGenderConflicts(
        added: String,
        existing: List<String>,
        genderRule: Boolean,
        moved: MutableList<String>,
        negatives: MutableList<String>,
    ) {
        val gender = gender(added).takeIf { genderRule }
        if (gender != null) {
            existing.filter { gender(it).let { g -> g != null && g != gender } }.forEach { move(moved, it) }
            negatives += if (gender == Gender.Male) "female vocals" else "male vocals"
        }
    }

    private fun moveVocalConflicts(
        added: String,
        existing: List<String>,
        moved: MutableList<String>,
        negatives: MutableList<String>,
    ) {
        if (isInstrumental(added)) {
            existing.filter(::isVocal).takeIf { it.isNotEmpty() }?.let {
                it.forEach { style -> move(moved, style) }
                negatives += "vocals"
            }
        } else if (isVocal(added)) {
            existing.filter(::isInstrumental).takeIf { it.isNotEmpty() }?.let {
                it.forEach { style -> move(moved, style) }
                negatives += "instrumental"
            }
        }
    }

    fun contradictedNegatives(
        added: List<String>,
        negatives: List<String>,
    ): List<String> {
        val genderRule = added.mapNotNull(::gender).toSet().size <= 1
        return negatives.filter { n ->
            added.any { a ->
                val g = gender(a).takeIf { genderRule }
                val sameGender = g != null && gender(n) == g && (isVocal(n) || voiceTypes(n).isNotEmpty())
                val sameVoiceType = voiceTypes(n).intersect(voiceTypes(a)).isNotEmpty()
                val vocalVsInstrumental = isVocal(a) && (isInstrumental(n) || n.trim().lowercase() in genericVocals)
                sameGender || sameVoiceType || vocalVsInstrumental || (isInstrumental(a) && isInstrumental(n))
            }
        }
    }

    private val genericVocals = setOf("vocals", "vocal")
}

package com.example.engine

/**
 * Tier 0: Input Normalization & Compound Splitter (Process A).
 * - Normalizes contractions ("what's" -> "what is", "don't" -> "do not")
 * - Strips spoken self-corrections ("wait not X, do Y", "actually ...")
 * - Detects conditional logic ("if X then Y") -> requires LLM
 * - Splits chained commands ("A and then B", "A and B")
 */
object InputNormalizer {

    data class NormalizedInput(
        val original: String,
        val cleanedFull: String,
        val subCommands: List<String>,
        val hasConditionalLogic: Boolean,
        val strippedCorrection: Boolean,
        val wakeWordStripped: Boolean = false,
        val isWakeWordOnly: Boolean = false
    )

    private val selfCorrectionPrefixes = listOf(
        Regex("^.*?\\b(?:wait no|wait not [^,;]+[,;]?|scratch that[,;]?|no actually[,;]?|sorry i meant)\\s+", RegexOption.IGNORE_CASE)
    )

    private val greetingWakeRegex = Regex(
        "^(?:uh+|um+|oh+|ah+|excuse\\s+me|hey\\s+there)?[\\s,]*(?:hey|ok|okay|hi|yo|hello|dear|a|eh|ey|ay)[\\s,]+(?:nova|novah|novaa|no\\s*va|noah|noba|nava|nora|nola|novas|nova's)(?:[\\s,.:!?]+(.*))?$",
        RegexOption.IGNORE_CASE
    )

    private val compactWakeRegex = Regex(
        "^(?:heynova|oknova|okaynova|hinova|heynoba|heynava)(?:[\\s,.:!?]+(.*))?$",
        RegexOption.IGNORE_CASE
    )

    private val directPunctuatedWakeRegex = Regex(
        "^(?:nova|novah|novaa|noba)(?:[\\s,.:!?]+(.*))?$",
        RegexOption.IGNORE_CASE
    )

    private val nonAssistantNovaPhraseRegex = Regex(
        "^nova\\s+(?:scotia|launcher|era|baby|award|awards|pro|series)\\b",
        RegexOption.IGNORE_CASE
    )

    private val conditionalPattern = Regex("\\b(if\\s+.+?\\s+then\\s+.+|whenever\\s+.+|unless\\s+.+)\\b", RegexOption.IGNORE_CASE)

    fun extractAfterWakePhrase(rawInput: String): Pair<String, Boolean> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return "" to false

        greetingWakeRegex.find(trimmed)?.let { m ->
            return m.groupValues[1].trim() to true
        }
        compactWakeRegex.find(trimmed)?.let { m ->
            return m.groupValues[1].trim() to true
        }
        if (!nonAssistantNovaPhraseRegex.containsMatchIn(trimmed)) {
            directPunctuatedWakeRegex.find(trimmed)?.let { m ->
                return m.groupValues[1].trim() to true
            }
        }
        return trimmed to false
    }

    fun normalize(rawInput: String): NormalizedInput {
        val (withoutWake, wakeStripped) = extractAfterWakePhrase(rawInput)
        var text = withoutWake.trim()
        var stripped = false

        if (wakeStripped && text.isEmpty()) {
            return NormalizedInput(
                original = rawInput,
                cleanedFull = "",
                subCommands = emptyList(),
                hasConditionalLogic = false,
                strippedCorrection = false,
                wakeWordStripped = true,
                isWakeWordOnly = true
            )
        }

        // Normalize common English contractions before matching (Adreno FP16 / regex safety)
        text = normalizeContractions(text)

        // Handle inline self-correction: e.g. "Turn on wifi wait no turn on bluetooth"
        val waitNotRegex = Regex("^.+?\\b(?:wait,?\\s+no|wait,?\\s+not\\s+[^,]+|scratch\\s+that|actually\\s+no)\\b[,\\s]*(.+)$", RegexOption.IGNORE_CASE)
        waitNotRegex.find(text)?.let { match ->
            val corrected = match.groupValues[1].trim()
            if (corrected.isNotEmpty()) {
                text = corrected
                stripped = true
            }
        }

        if (!stripped) {
            for (pattern in selfCorrectionPrefixes) {
                val replaced = pattern.replace(text, "").trim()
                if (replaced.isNotEmpty() && replaced.length < text.length) {
                    text = replaced
                    stripped = true
                    break
                }
            }
        }

        val hasConditional = conditionalPattern.containsMatchIn(text)
        val commands = if (hasConditional) {
            listOf(text)
        } else {
            splitCompoundCommands(text)
        }

        return NormalizedInput(
            original = rawInput,
            cleanedFull = text,
            subCommands = commands,
            hasConditionalLogic = hasConditional,
            strippedCorrection = stripped,
            wakeWordStripped = wakeStripped,
            isWakeWordOnly = false
        )
    }

    fun normalizeContractions(input: String): String {
        return input
            .replace(Regex("\\bwhat's\\b", RegexOption.IGNORE_CASE), "what is")
            .replace(Regex("\\bwhats\\b", RegexOption.IGNORE_CASE), "what is")
            .replace(Regex("\\bdon't\\b", RegexOption.IGNORE_CASE), "do not")
            .replace(Regex("\\bi'm\\b", RegexOption.IGNORE_CASE), "i am")
            .replace(Regex("\\bit's\\b", RegexOption.IGNORE_CASE), "it is")
            .replace(Regex("\\bcan't\\b", RegexOption.IGNORE_CASE), "can not")
            .replace(Regex("\\bthat's\\b", RegexOption.IGNORE_CASE), "that is")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitCompoundCommands(text: String): List<String> {
        val parts = text
            .split(Regex("\\s+(?:and then|then)\\s+|\\s*;\\s*", RegexOption.IGNORE_CASE))
            .flatMap { segment ->
                // Split "X and Y" only when Y starts with an action verb so "Mom and Dad" stays intact
                val andMatch = Regex("^(.+?)\\s+and\\s+((?:turn|set|open|send|call|play|pause|stop|find|text|check|summarize|start)\\b.+)$", RegexOption.IGNORE_CASE)
                    .find(segment)
                if (andMatch != null) {
                    listOf(andMatch.groupValues[1].trim(), andMatch.groupValues[2].trim())
                } else {
                    listOf(segment.trim())
                }
            }
            .filter { it.isNotBlank() }

        return if (parts.isEmpty()) listOf(text) else parts
    }
}

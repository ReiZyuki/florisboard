/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import org.k3lp.runtime.K3Content
import java.io.File
import java.util.Locale

/**
 * Small, fully local next-word model backed by editable sentence patterns and
 * counts of suggestions the user has accepted. This is a statistical pattern
 * learner, not a generative neural-network model.
 */
object OfflinePatternEngine {
    private const val PATTERN_FILE = "pattern"
    private const val LEARNED_FILE = "pattern_learned.tsv"
    private val lock = Any()
    private val pendingContexts = mutableMapOf<String, String>()

    private fun userPatternFile(context: Context) = File(context.filesDir, PATTERN_FILE)
    private fun learnedFile(context: Context) = File(context.filesDir, LEARNED_FILE)

    fun patternFile(context: Context): File = userPatternFile(context)

    private fun ensurePatternFile(context: Context): File {
        val file = userPatternFile(context)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            runCatching {
                context.assets.open("ime/pattern").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure {
                file.writeText(
                    "bro ab tu apna idea\n" +
                    "ab tu apna pehla idea bata\n" +
                    "bilkul bro ab samjha\n" +
                    "hum isko offline banayenge\n"
                )
            }
        }
        return file
    }

    fun readPatterns(context: Context): List<String> = synchronized(lock) {
        ensurePatternFile(context).readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    fun writePatterns(context: Context, patterns: List<String>) = synchronized(lock) {
        val file = ensurePatternFile(context)
        file.writeText(patterns.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n", postfix = "\n"))
    }

    private fun normalize(text: String): List<String> =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun contextFrom(content: K3Content): String {
        val before = content.surroundingText.textBefore.toString()
        val composing = content.composingText.toString()
        // The composing word is not part of the four-word history; it is only a prefix filter.
        return normalize(before).takeLast(4).joinToString(" ")
    }

    private fun prefixFrom(content: K3Content): String =
        normalize(content.composingText.toString()).joinToString(" ")

    fun suggest(context: Context, content: K3Content, maxCount: Int, provider: SuggestionProvider): List<WordSuggestionCandidate> = synchronized(lock) {
        val history = contextFrom(content)
        val prefix = prefixFrom(content)
        if (history.isBlank() && prefix.isBlank()) return@synchronized emptyList()

        val scores = mutableMapOf<String, Double>()
        val patterns = readPatterns(context)
        val historyWords = normalize(history)
        for (line in patterns) {
            val words = normalize(line)
            if (words.size < 2) continue
            // Prefer the longest matching suffix, while allowing shorter contexts for sparse data.
            for (n in minOf(4, historyWords.size, words.size - 1) downTo 1) {
                val suffix = historyWords.takeLast(n)
                for (i in n until words.size) {
                    if (words.subList(i - n, i) == suffix) {
                        val next = words[i]
                        if (prefix.isBlank() || next.startsWith(prefix)) {
                            scores[next] = (scores[next] ?: 0.0) + n * 2.0
                        }
                    }
                }
            }
        }

        // Accepted suggestions contribute frequency-weighted local evidence.
        learnedFile(context).takeIf { it.exists() }?.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size == 3) {
                val learnedContext = parts[0]
                val word = parts[1]
                val count = parts[2].toDoubleOrNull() ?: 0.0
                if ((history == learnedContext || history.endsWith(" $learnedContext") ||
                    learnedContext.endsWith(" $history")) &&
                    (prefix.isBlank() || word.startsWith(prefix))) {
                    scores[word] = (scores[word] ?: 0.0) + count * 3.0
                }
            }
        }

        val totalScore = scores.values.sum().coerceAtLeast(1.0)
        val result = scores.entries.sortedByDescending { it.value }.take(maxCount).map { entry ->
            val confidence = (entry.value / totalScore).coerceIn(0.0, 1.0)
            val candidate = WordSuggestionCandidate(
                text = entry.key,
                confidence = confidence,
                sourceProvider = provider,
            )
            pendingContexts[entry.key] = history
            candidate
        }
        result
    }

    data class ChatReply(val text: String, val confidencePercent: Int)

    /**
     * Generates a short reply by repeatedly choosing the highest-scoring next word
     * from local sentence patterns and accepted-word counts.
     */
    fun chatReply(context: Context, prompt: String): ChatReply = synchronized(lock) {
        val promptWords = normalize(prompt)
        val patterns = readPatterns(context)
        if (patterns.isEmpty()) {
            return@synchronized ChatReply("Pattern file empty hai. App se Hinglish examples add karo.", 0)
        }

        val bestPattern = patterns.maxByOrNull { line ->
            val words = normalize(line)
            val overlap = words.count { it in promptWords }
            val phraseBonus = if (promptWords.isNotEmpty() && line.lowercase().contains(promptWords.joinToString(" "))) 4 else 0
            overlap * 2 + phraseBonus
        }
        val bestWords = bestPattern?.let(::normalize).orEmpty()
        val overlap = bestWords.count { it in promptWords }.coerceAtMost(promptWords.size)
        var confidence = if (promptWords.isEmpty()) 15 else
            ((overlap.toDouble() / promptWords.size.coerceAtLeast(1)) * 65.0 + 20.0).toInt().coerceIn(10, 85)

        val seed = if (promptWords.isNotEmpty()) promptWords.takeLast(4) else emptyList()
        val output = mutableListOf<String>()
        if (bestWords.isNotEmpty() && overlap > 0) {
            // Start with the most relevant stored pattern, then use the same local
            // next-word scoring used by the keyboard to continue its final context.
            output.addAll(bestWords)
            repeat(8) { step ->
                val history = output.takeLast(4)
                val nextScores = mutableMapOf<String, Double>()
                patterns.forEach { line ->
                    val words = normalize(line)
                    for (n in minOf(4, history.size, words.size - 1) downTo 1) {
                        if (words.size > n) {
                            for (i in n until words.size) {
                                if (words.subList(i - n, i) == history.takeLast(n)) {
                                    nextScores[words[i]] = (nextScores[words[i]] ?: 0.0) + n * 2.0
                                }
                            }
                        }
                    }
                }
                learnedFile(context).takeIf { it.exists() }?.forEachLine { line ->
                    val parts = line.split('\t')
                    if (parts.size == 3 && history.joinToString(" ").endsWith(parts[0])) {
                        nextScores[parts[1]] = (nextScores[parts[1]] ?: 0.0) + (parts[2].toDoubleOrNull() ?: 0.0) * 3.0
                    }
                }
                val totalNextScore = nextScores.values.sum()
                val bestNext = nextScores.maxByOrNull { it.value }
                if (step == 0 && bestNext != null && totalNextScore > 0.0) {
                    confidence = ((bestNext.value / totalNextScore) * 100.0).toInt().coerceIn(1, 99)
                }
                val next = bestNext?.key
                if (next != null && output.size < 24) output.add(next)
            }
        } else {
            return@synchronized ChatReply(
                "Mere stored patterns mein is message ka strong match nahi mila. Pattern file mein is topic ke examples add karo.",
                confidence
            )
        }
        ChatReply(output.distinct().joinToString(" ").replaceFirstChar { it.uppercase() }, confidence)
    }

    fun learnAccepted(context: Context, candidate: SuggestionCandidate) = synchronized(lock) {
        val word = candidate.text.toString().trim().lowercase(Locale.ROOT)
        if (word.isBlank()) return@synchronized
        val history = pendingContexts.remove(word).orEmpty()
        if (history.isBlank()) return@synchronized

        val counts = linkedMapOf<Pair<String, String>, Int>()
        learnedFile(context).takeIf { it.exists() }?.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size == 3) {
                val count = parts[2].toIntOrNull() ?: 0
                counts[parts[0] to parts[1]] = count
            }
        }
        val key = history to word
        counts[key] = (counts[key] ?: 0) + 1
        learnedFile(context).writeText(counts.entries.joinToString("\n", postfix = "\n") {
            "${it.key.first}\t${it.key.second}\t${it.value}"
        })
    }
}

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
 * Local pattern-based next-word engine. It is deliberately lightweight, not a
 * neural language model. Pattern and learned data are cached in memory and
 * indexed by 1-4 word suffixes so keystrokes do not rescan every sentence.
 */
object OfflinePatternEngine {
    private const val PATTERN_FILE = "pattern"
    private const val LEARNED_FILE = "pattern_learned.tsv"
    private const val MAX_CONTEXT_WORDS = 4
    private val lock = Any()
    private val pendingContexts = mutableMapOf<String, String>()
    private val punctuationRegex = Regex("[^\\p{L}\\p{N}' ]")
    private val whitespaceRegex = Regex("\\s+")

    private data class FileStamp(val modified: Long, val length: Long)

    private data class Snapshot(
        val patternStamp: FileStamp,
        val learnedStamp: FileStamp?,
        val patterns: List<List<String>>,
        val nextWordIndex: Map<String, Map<String, Double>>,
        val learnedIndex: Map<String, Map<String, Double>>,
    )

    @Volatile private var cachedSnapshot: Snapshot? = null

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

    private fun stamp(file: File): FileStamp = FileStamp(file.lastModified(), file.length())

    private fun normalize(text: String): List<String> {
        val cleaned = text.lowercase(Locale.ROOT).replace(punctuationRegex, " ").trim()
        return if (cleaned.isEmpty()) emptyList() else cleaned.split(whitespaceRegex)
    }

    private fun suffixes(words: List<String>, max: Int = MAX_CONTEXT_WORDS): List<String> {
        if (words.isEmpty()) return emptyList()
        return (1..minOf(max, words.size)).map { n -> words.takeLast(n).joinToString(" ") }
    }

    private fun buildSnapshot(context: Context, patternFile: File, learned: File): Snapshot {
        val patterns = patternFile.readLines().asSequence()
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toList()

        val nextMutable = mutableMapOf<String, MutableMap<String, Double>>()
        for (words in patterns) {
            for (i in words.indices) {
                for (n in 1..minOf(MAX_CONTEXT_WORDS, i)) {
                    val key = words.subList(i - n, i).joinToString(" ")
                    val next = words[i]
                    val wordScores = nextMutable.getOrPut(key) { mutableMapOf() }
                    wordScores[next] = (wordScores[next] ?: 0.0) + n * 2.0
                }
            }
        }

        // Aggregate repeated rows and index each learned context by its suffix.
        val learnedCounts = mutableMapOf<Pair<String, String>, Double>()
        if (learned.exists()) {
            learned.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size == 3) {
                    val count = parts[2].toDoubleOrNull() ?: 0.0
                    if (count > 0.0) {
                        val key = parts[0].trim() to parts[1].trim()
                        learnedCounts[key] = (learnedCounts[key] ?: 0.0) + count
                    }
                }
            }
        }
        val learnedMutable = mutableMapOf<String, MutableMap<String, Double>>()
        for ((key, count) in learnedCounts) {
            val (learnedContext, word) = key
            val contextWords = normalize(learnedContext)
            for (suffix in suffixes(contextWords)) {
                val scores = learnedMutable.getOrPut(suffix) { mutableMapOf() }
                scores[word] = (scores[word] ?: 0.0) + count * 3.0
            }
        }

        return Snapshot(
            patternStamp = stamp(patternFile),
            learnedStamp = if (learned.exists()) stamp(learned) else null,
            patterns = patterns,
            nextWordIndex = nextMutable.mapValues { it.value.toMap() },
            learnedIndex = learnedMutable.mapValues { it.value.toMap() },
        )
    }

    private fun snapshot(context: Context): Snapshot = synchronized(lock) {
        val patternsFile = ensurePatternFile(context)
        val learned = learnedFile(context)
        val current = cachedSnapshot
        val patternStamp = stamp(patternsFile)
        val learnedStamp = if (learned.exists()) stamp(learned) else null
        if (current != null && current.patternStamp == patternStamp && current.learnedStamp == learnedStamp) {
            return@synchronized current
        }
        buildSnapshot(context, patternsFile, learned).also { cachedSnapshot = it }
    }

    fun readPatterns(context: Context): List<String> = synchronized(lock) {
        ensurePatternFile(context).readLines().map { it.trim() }.filter { it.isNotBlank() }
    }

    fun writePatterns(context: Context, patterns: List<String>) = synchronized(lock) {
        val file = ensurePatternFile(context)
        file.writeText(patterns.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n", postfix = "\n"))
        cachedSnapshot = null
    }

    private fun contextFrom(content: K3Content): String =
        normalize(content.surroundingText.textBefore.toString()).takeLast(MAX_CONTEXT_WORDS).joinToString(" ")

    private fun prefixFrom(content: K3Content): String =
        normalize(content.composingText.toString()).joinToString(" ")

    private fun addScores(
        target: MutableMap<String, Double>,
        source: Map<String, Double>?,
        prefix: String,
    ) {
        if (source == null) return
        for ((word, score) in source) {
            if (prefix.isBlank() || word.startsWith(prefix)) {
                target[word] = (target[word] ?: 0.0) + score
            }
        }
    }

    private fun scoresFor(history: List<String>, prefix: String, data: Snapshot): MutableMap<String, Double> {
        val scores = mutableMapOf<String, Double>()
        for (suffix in suffixes(history)) {
            addScores(scores, data.nextWordIndex[suffix], prefix)
            addScores(scores, data.learnedIndex[suffix], prefix)
        }
        return scores
    }

    fun suggest(
        context: Context,
        content: K3Content,
        maxCount: Int,
        provider: SuggestionProvider,
    ): List<WordSuggestionCandidate> = synchronized(lock) {
        if (maxCount <= 0) return@synchronized emptyList()
        val history = contextFrom(content)
        val prefix = prefixFrom(content)
        if (history.isBlank() && prefix.isBlank()) return@synchronized emptyList()

        val data = snapshot(context)
        val scores = scoresFor(normalize(history), prefix, data)
        val totalScore = scores.values.sum().coerceAtLeast(1.0)
        scores.entries.sortedByDescending { it.value }.take(maxCount).map { entry ->
            pendingContexts[entry.key] = history
            WordSuggestionCandidate(
                text = entry.key,
                confidence = (entry.value / totalScore).coerceIn(0.0, 1.0),
                sourceProvider = provider,
            )
        }
    }

    data class ChatReply(val text: String, val confidencePercent: Int)

    fun chatReply(context: Context, prompt: String): ChatReply = synchronized(lock) {
        val promptWords = normalize(prompt)
        val data = snapshot(context)
        if (data.patterns.isEmpty()) {
            return@synchronized ChatReply("Pattern file empty hai. App se Hinglish examples add karo.", 0)
        }

        val bestPattern = data.patterns.maxByOrNull { words ->
            val overlap = words.count { it in promptWords }
            val phraseBonus = if (promptWords.isNotEmpty() &&
                words.joinToString(" ").contains(promptWords.joinToString(" "))) 4 else 0
            overlap * 2 + phraseBonus
        }
        val bestWords = bestPattern.orEmpty()
        val overlap = bestWords.count { it in promptWords }.coerceAtMost(promptWords.size)
        var confidence = if (promptWords.isEmpty()) 15 else
            ((overlap.toDouble() / promptWords.size.coerceAtLeast(1)) * 65.0 + 20.0).toInt().coerceIn(10, 85)

        if (bestWords.isEmpty() || overlap == 0) {
            return@synchronized ChatReply(
                "Mere stored patterns mein is message ka strong match nahi mila. Pattern file mein is topic ke examples add karo.",
                confidence,
            )
        }

        val output = bestWords.toMutableList()
        repeat(8) { step ->
            if (output.size >= 24) return@repeat
            val nextScores = scoresFor(output.takeLast(MAX_CONTEXT_WORDS), "", data)
            val total = nextScores.values.sum()
            val bestNext = nextScores.maxByOrNull { it.value }
            if (step == 0 && bestNext != null && total > 0.0) {
                confidence = ((bestNext.value / total) * 100.0).toInt().coerceIn(1, 99)
            }
            val next = bestNext?.key ?: return@repeat
            output.add(next)
        }
        ChatReply(output.joinToString(" ").replaceFirstChar { it.uppercase() }, confidence)
    }

    fun learnAccepted(context: Context, candidate: SuggestionCandidate) = synchronized(lock) {
        val word = candidate.text.toString().trim().lowercase(Locale.ROOT)
        if (word.isBlank()) return@synchronized
        val history = pendingContexts.remove(word).orEmpty()
        if (history.isBlank()) return@synchronized

        val file = learnedFile(context)
        file.parentFile?.mkdirs()
        // Append one small event instead of reading and rewriting the entire learning file.
        file.appendText("$history\t$word\t1\n")
        cachedSnapshot = null
    }
}

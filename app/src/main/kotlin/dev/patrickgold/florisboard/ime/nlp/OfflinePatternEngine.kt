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

    data class StructuredText(
        val chunks: List<String>,
        val factCandidates: List<String>,
        val keywords: List<String>,
    )

    private val sentenceBoundaryRegex = Regex("(?<=[.!?।])\\s+|\\n+")
    private val factCueRegex = Regex(
        """\b(is|are|was|were|has|have|had|because|means|caused|decided|lives|works|likes|hates|became|met|found|told|said|known|called|hai|hain|tha|thi|the|hota|hoti|hua|hui|kiya|karta|karti|karte|rahta|rahti|rehta|rehte)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val keywordStopWords = setOf(
        "the", "and", "for", "that", "this", "with", "from", "have", "has", "was", "were",
        "are", "is", "you", "your", "but", "not", "they", "them", "then", "than", "into",
        "about", "what", "when", "where", "which", "while", "will", "would", "could",
        "hai", "hain", "tha", "thi", "the", "aur", "ke", "ki", "ka", "ko", "me", "mein",
        "se", "par", "ye", "woh", "ek", "to", "bhi", "nahi", "nahin", "bro", "because",
    )

    /**
     * Deterministically divides supplied text into bounded chunks and extracts
     * candidate fact sentences and frequent keywords. These are heuristic
     * candidates, not guaranteed semantic facts or a language-model summary.
     */
    fun structureText(text: String, maxChunkChars: Int = 700): StructuredText {
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (cleaned.isEmpty()) return StructuredText(emptyList(), emptyList(), emptyList())

        val sentences = cleaned.split(sentenceBoundaryRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        val limit = maxChunkChars.coerceAtLeast(120)
        for (sentence in sentences) {
            var remaining = sentence
            while (remaining.length > limit) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current = StringBuilder()
                }
                chunks.add(remaining.take(limit).trim())
                remaining = remaining.drop(limit).trimStart()
            }
            if (remaining.isEmpty()) continue
            if (current.isNotEmpty() && current.length + remaining.length + 1 > limit) {
                chunks.add(current.toString().trim())
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(remaining)
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())

        val facts = sentences
            .filter { it.length >= 18 && factCueRegex.containsMatchIn(it) }
            .distinct()
            .take(40)

        val keywords = normalize(cleaned)
            .filter { it.length >= 3 && it !in keywordStopWords && it.any(Char::isLetter) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(20)
            .map { it.key }

        return StructuredText(chunks, facts, keywords)
    }

    private fun bigrams(words: List<String>): Set<String> =
        if (words.size < 2) emptySet()
        else (0 until words.lastIndex).map { "${words[it]} ${words[it + 1]}" }.toSet()

    /**
     * Ranks stored examples using token coverage, example precision, shared
     * two-word phrases, and ordered phrase matches. This is retrieval scoring,
     * not semantic understanding.
     */
    private fun patternMatchScore(promptWords: List<String>, patternWords: List<String>): Double {
        if (promptWords.isEmpty() || patternWords.isEmpty()) return 0.0
        val promptSet = promptWords.toSet()
        val patternSet = patternWords.toSet()
        val overlap = promptSet.intersect(patternSet).size
        if (overlap == 0) return 0.0

        val coverage = overlap.toDouble() / promptSet.size.coerceAtLeast(1)
        val precision = overlap.toDouble() / patternSet.size.coerceAtLeast(1)
        val promptBigrams = bigrams(promptWords)
        val patternBigrams = bigrams(patternWords)
        val bigramScore = if (promptBigrams.isEmpty()) 0.0 else
            promptBigrams.intersect(patternBigrams).size.toDouble() / promptBigrams.size
        val promptPhrase = promptWords.joinToString(" ")
        val patternPhrase = patternWords.joinToString(" ")
        val phraseBonus = if (promptPhrase.length >= 4 &&
            (patternPhrase.contains(promptPhrase) || promptPhrase.contains(patternPhrase))) 1.0 else 0.0

        return coverage * 0.50 + precision * 0.20 + bigramScore * 0.20 + phraseBonus * 0.10
    }

    fun chatReply(context: Context, prompt: String): ChatReply = synchronized(lock) {
        // Long pasted text is structured first rather than treated as one giant query.
        // Nothing is persisted: the extracted structure exists only for this reply.
        if (prompt.length > 1200) {
            val structured = structureText(prompt)
            if (structured.chunks.isNotEmpty()) {
                val report = buildString {
                    append("Text structure complete\n")
                    append("Chunks: ${structured.chunks.size}\n")
                    append("Fact candidates: ${structured.factCandidates.size}\n")
                    if (structured.keywords.isNotEmpty()) {
                        append("\nKey terms: ${structured.keywords.joinToString(", ")}")
                    }
                    if (structured.factCandidates.isNotEmpty()) {
                        append("\n\nPossible facts (heuristic):")
                        structured.factCandidates.take(10).forEachIndexed { index, fact ->
                            append("\\n${index + 1}. $fact")
                        }
                    } else {
                        append("\n\nNo clear fact-like sentences were detected by the simple rule set.")
                    }
                    append("\n\nNote: these are rule-based candidates, not verified facts.")
                }
                return@synchronized ChatReply(report, 60)
            }
        }

        val promptWords = normalize(prompt)
        val data = snapshot(context)
        if (data.patterns.isEmpty()) {
            return@synchronized ChatReply("Pattern file empty hai. App se Hinglish examples add karo.", 0)
        }
        if (promptWords.isEmpty()) {
            return@synchronized ChatReply("Kuch text likh bro, phir stored patterns se match karunga.", 0)
        }

        val ranked = data.patterns
            .map { words -> words to patternMatchScore(promptWords, words) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val bestWords = ranked.firstOrNull()?.first.orEmpty()
        val bestScore = ranked.firstOrNull()?.second ?: 0.0
        val secondScore = ranked.getOrNull(1)?.second ?: 0.0
        val margin = (bestScore - secondScore).coerceAtLeast(0.0)
        val confidence = ((bestScore * 75.0) + (margin * 25.0)).toInt().coerceIn(1, 95)

        if (bestWords.isEmpty()) {
            return@synchronized ChatReply(
                "Mere stored patterns mein is message ka strong match nahi mila. Pattern file mein is topic ke examples add karo.",
                0,
            )
        }

        // Keep the best matching example as the response base. Extend it only
        // when the local pattern/learning index has a continuation, and stop
        // when the engine starts cycling through the same recent words.
        val output = bestWords.toMutableList()
        val seenStates = mutableSetOf(output.takeLast(MAX_CONTEXT_WORDS).joinToString(" "))
        repeat(8) {
            if (output.size >= 32) return@repeat
            val nextScores = scoresFor(output.takeLast(MAX_CONTEXT_WORDS), "", data)
            val recent = output.takeLast(3).toSet()
            val next = nextScores.entries
                .filter { it.key !in recent }
                .maxByOrNull { it.value }
                ?.key ?: return@repeat
            output.add(next)
            val state = output.takeLast(MAX_CONTEXT_WORDS).joinToString(" ")
            if (!seenStates.add(state)) {
                output.removeAt(output.lastIndex)
                return@repeat
            }
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

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

import java.util.Locale
import kotlin.math.abs

/**
 * Lightweight offline typo correction backed by the active dictionary.
 * Candidates are ranked by edit distance and length; this is not semantic AI.
 */
object AutoCorrectEngine {
    private val lock = Any()
    @Volatile private var indexedVocabulary: List<String>? = null
    @Volatile private var wordsByLength: Map<Int, List<String>> = emptyMap()

    private fun ensureIndex(vocabulary: List<String>) = synchronized(lock) {
        if (indexedVocabulary === vocabulary) return@synchronized
        wordsByLength = vocabulary.asSequence()
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() && it.all(Char::isLetter) }
            .distinct()
            .groupBy { it.length }
        indexedVocabulary = vocabulary
    }

    /**
     * Returns high-confidence candidates only. For 4-7 letter words, one edit
     * is allowed, so "karami" can match "harami" if it exists in the dictionary.
     */
    fun suggestions(
        input: String,
        vocabulary: List<String>,
        maxCount: Int = 3,
    ): List<String> {
        val word = input.lowercase(Locale.ROOT).trim()
        if (word.length < 3 || word.length > 32 || maxCount <= 0) return emptyList()
        ensureIndex(vocabulary)

        val maxDistance = when {
            word.length <= 7 -> 1
            word.length <= 12 -> 2
            else -> 3
        }
        val candidates = (maxOf(1, word.length - 1)..(word.length + 1))
            .flatMap { wordsByLength[it].orEmpty() }
            .asSequence()
            .filter { it != word && abs(it.length - word.length) <= 1 }
            .mapNotNull { candidate ->
                val distance = editDistance(word, candidate, maxDistance)
                if (distance <= maxDistance) candidate to distance else null
            }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }
                .thenBy { abs(it.first.length - word.length) }
                .thenByDescending { it.first.length })
            .take(maxCount)
            .map { it.first }
            .toList()

        if (candidates.isEmpty()) return emptyList()
        return candidates.map { candidate ->
            when {
                input.firstOrNull()?.isUpperCase() == true ->
                    candidate.replaceFirstChar { it.uppercase(Locale.ROOT) }
                else -> candidate
            }
        }
    }

    private fun editDistance(left: String, right: String, limit: Int): Int {
        if (abs(left.length - right.length) > limit) return limit + 1
        if (left == right) return 0

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            var rowMinimum = current[0]
            for (j in right.indices) {
                val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
                val insertion = current[j] + 1
                val deletion = previous[j + 1] + 1
                current[j + 1] = minOf(substitution, insertion, deletion)
                rowMinimum = minOf(rowMinimum, current[j + 1])
            }
            if (rowMinimum > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}

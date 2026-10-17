/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * Licensed under the Apache License, Version 2.0
 */
package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * Offline feedback records and candidate scoring for the pattern chat engine.
 * The 30/30/40 values are source weights, not a guarantee of answer correctness.
 */
object FeedbackLearningEngine {
    private const val LIKES_FILE = "feedback_likes.tsv"
    private const val CORRECTIONS_FILE = "feedback_corrections.tsv"
    private val lock = Any()
    private val tokenRegex = Regex("[\\p{L}\\p{N}']+")

    data class Correction(
        val input: String,
        val wrongOutput: String,
        val correctOutput: String,
    )

    private fun likesFile(context: Context) = File(context.filesDir, LIKES_FILE)
    private fun correctionsFile(context: Context) = File(context.filesDir, CORRECTIONS_FILE)

    private fun encode(value: String): String = value
        .replace("%", "%25")
        .replace("\t", "%09")
        .replace("\r", "%0D")
        .replace("\n", "%0A")

    private fun decode(value: String): String = value
        .replace("%0A", "\n")
        .replace("%0D", "\r")
        .replace("%09", "\t")
        .replace("%25", "%")

    private fun append(file: File, fields: List<String>) {
        file.parentFile?.mkdirs()
        file.appendText(fields.joinToString("\t", transform = ::encode) + "\n")
    }

    fun recordLike(context: Context, input: String, output: String) = synchronized(lock) {
        if (input.isBlank() || output.isBlank()) return@synchronized
        append(likesFile(context), listOf(input.trim(), output.trim()))
    }

    fun recordCorrection(context: Context, input: String, wrongOutput: String, correctOutput: String) =
        synchronized(lock) {
            if (input.isBlank() || wrongOutput.isBlank() || correctOutput.isBlank()) return@synchronized
            append(correctionsFile(context), listOf(input.trim(), wrongOutput.trim(), correctOutput.trim()))
        }

    private fun readRows(file: File, columns: Int): List<List<String>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != columns) null else fields.map(::decode)
            }
        }.getOrDefault(emptyList())
    }

    fun likedExamples(context: Context): List<Pair<String, String>> = synchronized(lock) {
        readRows(likesFile(context), 2).map { it[0] to it[1] }
    }

    fun corrections(context: Context): List<Correction> = synchronized(lock) {
        readRows(correctionsFile(context), 3).map { Correction(it[0], it[1], it[2]) }
    }

    private fun tokens(text: String): List<String> =
        tokenRegex.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    private fun similarity(left: String, right: String): Double {
        val a = tokens(left).toSet()
        val b = tokens(right).toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / max(a.size, b.size)
    }

    fun scoreCandidate(context: Context, input: String, candidate: String, storedPatternScore: Double): Double {
        val stored = storedPatternScore.coerceIn(0.0, 1.0)
        val likes = likedExamples(context)
        val likedScore = likes.maxOfOrNull { (savedInput, savedOutput) ->
            similarity(input, savedInput) * similarity(candidate, savedOutput)
        } ?: 0.0

        val records = corrections(context)
        var correctionReward = 0.0
        for (record in records) {
            val relevance = similarity(input, record.input)
            if (relevance < 0.15) continue
            val correctMatch = similarity(candidate, record.correctOutput)
            val wrongMatch = similarity(candidate, record.wrongOutput)
            correctionReward = max(correctionReward, relevance * (correctMatch - wrongMatch).coerceIn(-1.0, 1.0))
        }

        // The 40% correction channel is neutral when there is no related correction.
        val correctionScore = (0.5 + correctionReward / 2.0).coerceIn(0.0, 1.0)
        return (stored * 0.30 + likedScore * 0.30 + correctionScore * 0.40).coerceIn(0.0, 1.0)
    }

    /** Structure-only confidence: matching adjacent word pairs, not semantic truth. */
    fun structureConfidence(candidate: String, examples: List<String>): Int {
        val words = tokens(candidate)
        if (words.size < 2 || examples.isEmpty()) return 0
        val exampleTokens = examples.map(::tokens).filter { it.isNotEmpty() }
        val knownBigrams = exampleTokens.flatMap { row ->
            (0 until row.lastIndex).map { row[it] + " " + row[it + 1] }
        }.toSet()
        val candidateBigrams = (0 until words.lastIndex).map { words[it] + " " + words[it + 1] }
        val pairScore = candidateBigrams.count { it in knownBigrams }.toDouble() / candidateBigrams.size
        val phrase = words.joinToString(" ")
        val phraseSeen = exampleTokens.any {
            val example = it.joinToString(" ")
            example.contains(phrase) || phrase.contains(example)
        }
        return ((pairScore * 85.0) + if (phraseSeen) 15.0 else 0.0).toInt().coerceIn(0, 100)
    }
}

/*
 * Copyright 2026 EhViewer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.download

import com.hippo.ehviewer.client.data.GalleryInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Normalizer
import java.util.Locale

/**
 * Finds galleries whose titles are identical or very similar.
 *
 * Titles are normalized first (full/half width, case, punctuation, leading event tag such
 * as "(C103)", and dates such as "2024年1月" / "2024.01.05" / "20240105" are removed), so
 * re-uploads that only change the date are treated as identical. Remaining titles are then
 * compared with a character-bigram Dice coefficient to catch other small edits.
 *
 * Titles that are the same except for numbers (e.g. "Vol.1" / "Vol.2", "第1話" / "第2話")
 * are considered different works and are NOT grouped.
 */
object DuplicateTitleFinder {
    private const val SIMILARITY_THRESHOLD = 0.85
    private const val MIN_FUZZY_LENGTH = 6

    private val EVENT_PREFIX = Regex("""^\s*\([^)]*\)""")
    private val DATE_PATTERNS = listOf(
        // Jan 2024, Feb. 5
        Regex("""\b(?:jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s*\d{1,4}"""),
        // 2024年1月5日, 2024.01.05, 2024-01, 24/1, 2024年1月号
        Regex("""\d{2,4}\s*[年./\-_]\s*\d{1,2}\s*(?:[月./\-_]\s*\d{1,2}\s*日?|月号?)?"""),
        // 1月, 1月5日, 1月号
        Regex("""\d{1,2}\s*月\s*(?:\d{1,2}\s*日|号)?"""),
        // 20240105, 240105
        Regex("""(?<!\d)\d{6,8}(?!\d)"""),
        // 2024
        Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)"""),
    )
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val DIGITS = Regex("""\d+""")

    class Result(
        /** gid -> group id, only for galleries that belong to a group of 2 or more */
        val groupOf: Map<Long, Int>,
        val groupCount: Int,
    )

    private class Entry(
        val owner: Int,
        val bigrams: IntArray,
        val skeleton: String,
        val digits: List<String>,
    )

    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        s = EVENT_PREFIX.replace(s, "")
        for (pattern in DATE_PATTERNS) {
            s = pattern.replace(s, " ")
        }
        return NON_WORD.replace(s, "")
    }

    suspend fun find(infos: List<GalleryInfo>): Result {
        val n = infos.size
        val parent = IntArray(n) { it }
        fun findRoot(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = findRoot(a)
            val rb = findRoot(b)
            if (ra != rb) parent[rb] = ra
        }

        // 1. Exact match after normalization (covers "only the date changed")
        val exact = HashMap<String, Int>()
        for ((index, info) in infos.withIndex()) {
            val variants = listOfNotNull(info.title, info.titleJpn)
                .filter { it.isNotBlank() }
                .map { normalize(it) }
                .filter { it.isNotEmpty() }
                .distinct()
            for (v in variants) {
                val first = exact[v]
                if (first == null) exact[v] = index else union(first, index)
            }
        }
        currentCoroutineContext().ensureActive()

        // 2. Fuzzy match between distinct normalized titles
        val entries = exact.entries
            .filter { it.key.length >= MIN_FUZZY_LENGTH }
            .map { (norm, owner) ->
                Entry(
                    owner = owner,
                    bigrams = bigrams(norm),
                    skeleton = DIGITS.replace(norm, "#"),
                    digits = DIGITS.findAll(norm).map { it.value.trimStart('0') }.toList(),
                )
            }
            .sortedBy { it.bigrams.size }
        val maxRatio = (2 - SIMILARITY_THRESHOLD) / SIMILARITY_THRESHOLD
        for (i in entries.indices) {
            if (i and 0xFF == 0) currentCoroutineContext().ensureActive()
            val a = entries[i]
            val limit = a.bigrams.size * maxRatio + 1
            for (j in i + 1 until entries.size) {
                val b = entries[j]
                if (b.bigrams.size > limit) break
                if (findRoot(a.owner) == findRoot(b.owner)) continue
                // Same title except numbers -> different volume / chapter, not a duplicate
                if (a.skeleton == b.skeleton && a.digits != b.digits) continue
                if (dice(a.bigrams, b.bigrams) >= SIMILARITY_THRESHOLD) {
                    union(a.owner, b.owner)
                }
            }
        }

        // 3. Collect groups with 2 or more galleries
        val members = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            members.getOrPut(findRoot(i)) { ArrayList() }.add(i)
        }
        val groupOf = HashMap<Long, Int>()
        var groupCount = 0
        for ((root, list) in members) {
            if (list.size < 2) continue
            groupCount++
            for (i in list) groupOf[infos[i].gid] = root
        }
        return Result(groupOf, groupCount)
    }

    private fun bigrams(s: String): IntArray {
        if (s.length < 2) return intArrayOf(s[0].code)
        val arr = IntArray(s.length - 1) { (s[it].code shl 16) or s[it + 1].code }
        arr.sort()
        return arr
    }

    private fun dice(a: IntArray, b: IntArray): Double {
        var i = 0
        var j = 0
        var match = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    match++
                    i++
                    j++
                }
                a[i] < b[j] -> i++
                else -> j++
            }
        }
        return 2.0 * match / (a.size + b.size)
    }
}

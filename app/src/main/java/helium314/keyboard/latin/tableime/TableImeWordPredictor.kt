// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class TableImeWordPredictor(
    val words: List<String>
) {
    /**
     * Given text before the cursor, predicts the next continuation words/characters.
     * Searches with longest suffix of [textBeforeCursor] first (e.g. 4, 3, 2, 1 characters).
     * Candidates are continuations (e.g. for prefix "香", word "香港" yields "港").
     * Candidates are deduplicated and sorted by continuation length (shorter first).
     */
    fun predict(textBeforeCursor: String, maxCandidates: Int = 500): List<String> {
        if (textBeforeCursor.isEmpty() || words.isEmpty()) return emptyList()

        val trimmed = textBeforeCursor.trimEnd()
        if (trimmed.isEmpty()) return emptyList()

        // Try prefixes from longest to shortest (up to 4 chars)
        val maxPrefixLen = minOf(4, trimmed.length)
        for (len in maxPrefixLen downTo 1) {
            val prefix = trimmed.substring(trimmed.length - len)
            val candidates = lookupContinuations(prefix, maxCandidates)
            if (candidates.isNotEmpty()) {
                return candidates
            }
        }
        return emptyList()
    }

    /**
     * Finds continuations for a given prefix in the sorted word list.
     */
    fun lookupContinuations(prefix: String, maxCandidates: Int = 500): List<String> {
        if (prefix.isEmpty()) return emptyList()

        val lo = lowerBound(words, prefix)
        val hi = upperBound(words, prefix + "\uffff")
        if (lo >= hi) return emptyList()

        // Collect all matching words
        val matches = ArrayList<String>()
        for (i in lo until hi) {
            val w = words[i]
            if (w.startsWith(prefix) && w.length > prefix.length) {
                matches.add(w)
            }
        }

        if (matches.isEmpty()) return emptyList()

        // Sort matches by continuation length (shorter continuations first),
        // preserving natural lexicographical order for same length.
        matches.sortWith { a, b ->
            val diff = (a.length - prefix.length).compareTo(b.length - prefix.length)
            if (diff != 0) diff else a.compareTo(b)
        }

        val results = ArrayList<String>(minOf(matches.size, maxCandidates))
        val seen = HashSet<String>()
        for (w in matches) {
            val continuation = w.substring(prefix.length)
            if (continuation.isNotEmpty() && seen.add(continuation)) {
                results.add(continuation)
                if (results.size >= maxCandidates) break
            }
        }
        return results
    }

    private fun lowerBound(list: List<String>, target: String): Int {
        var low = 0
        var high = list.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (list[mid] >= target) {
                high = mid
            } else {
                low = mid + 1
            }
        }
        return low
    }

    private fun upperBound(list: List<String>, target: String): Int {
        var low = 0
        var high = list.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (list[mid] > target) {
                high = mid
            } else {
                low = mid + 1
            }
        }
        return low
    }

    companion object {
        private const val TAG = "TableImeWordPredictor"

        fun loadFromAsset(context: Context, assetPath: String): TableImeWordPredictor {
            val words = ArrayList<String>()
            try {
                context.assets.open(assetPath).use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                val tabIdx = trimmed.indexOf('\t')
                                val word = if (tabIdx != -1) trimmed.substring(0, tabIdx).trim() else trimmed
                                if (word.isNotEmpty()) {
                                    words.add(word)
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load words from asset: $assetPath", e)
            }
            return TableImeWordPredictor(words)
        }
    }
}

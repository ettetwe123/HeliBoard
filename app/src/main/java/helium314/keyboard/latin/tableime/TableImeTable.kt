// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class TableImeTable(
    val baseEntries: List<Entry>,
    @Volatile var extendedEntries: List<Entry>? = null
) {
    data class Entry(val code: String, val text: String)

    /**
     * Looks up candidates for a given code.
     * Exact matches come first, followed by prefix matches.
     * Base entries come before extended entries.
     */
    fun lookup(
        code: String,
        includeExtended: Boolean = false,
        maxCandidates: Int = 500,
        frequentWords: Set<String>?
    ): List<String> = lookup(
        code,
        includeExtended,
        maxCandidates,
        frequentWords?.associateWith { 1 }
    )

    /**
     * Looks up candidates for a given code.
     * Option A ordering:
     * 1. Exact matches (frequent words sorted by usage count DESC, then non-frequent in table order).
     * 2. Prefix matches (frequent words sorted by usage count DESC, then non-frequent in table order).
     * Base entries come before extended entries.
     */
    @JvmOverloads
    fun lookup(
        code: String,
        includeExtended: Boolean = false,
        maxCandidates: Int = 500,
        frequentWordCounts: Map<String, Int>? = null
    ): List<String> {
        if (code.isEmpty()) return emptyList()
        val query = code.lowercase()
        val exactMatches = LinkedHashSet<String>()

        // 1. Exact matches from base
        collectExactMatches(baseEntries, query, exactMatches, maxCandidates)

        // 2. Exact matches from extended (if enabled and loaded)
        val ext = if (includeExtended) extendedEntries else null
        if (ext != null && exactMatches.size < maxCandidates) {
            collectExactMatches(ext, query, exactMatches, maxCandidates)
        }

        val prefixMatches = LinkedHashSet<String>()

        // 3. Prefix matches from base
        if (exactMatches.size + prefixMatches.size < maxCandidates) {
            collectPrefixMatches(baseEntries, query, prefixMatches, maxCandidates - exactMatches.size)
        }

        // 4. Prefix matches from extended
        if (ext != null && exactMatches.size + prefixMatches.size < maxCandidates) {
            collectPrefixMatches(ext, query, prefixMatches, maxCandidates - exactMatches.size)
        }

        if (frequentWordCounts.isNullOrEmpty()) {
            val candidates = ArrayList<String>(exactMatches.size + prefixMatches.size)
            candidates.addAll(exactMatches)
            candidates.addAll(prefixMatches)
            return candidates
        }

        // Option A: Exact matches (frequent sorted by count DESC, then non-frequent in table order),
        // followed by Prefix matches (frequent sorted by count DESC, then non-frequent in table order).
        val result = ArrayList<String>(exactMatches.size + prefixMatches.size)

        fun appendPartition(matches: Iterable<String>) {
            val frequentList = ArrayList<String>()
            val nonFrequentList = ArrayList<String>()
            for (word in matches) {
                if ((frequentWordCounts[word] ?: 0) > 0) {
                    frequentList.add(word)
                } else {
                    nonFrequentList.add(word)
                }
            }
            frequentList.sortWith { a, b ->
                val ca = frequentWordCounts[a] ?: 0
                val cb = frequentWordCounts[b] ?: 0
                cb.compareTo(ca)
            }
            result.addAll(frequentList)
            result.addAll(nonFrequentList)
        }

        appendPartition(exactMatches)
        appendPartition(prefixMatches)
        return result
    }

    private fun collectExactMatches(
        entries: List<Entry>,
        query: String,
        out: MutableSet<String>,
        max: Int
    ) {
        val lo = lowerBound(entries, query)
        var i = lo
        while (i < entries.size && entries[i].code == query && out.size < max) {
            out.add(entries[i].text)
            i++
        }
    }

    private fun collectPrefixMatches(
        entries: List<Entry>,
        query: String,
        out: MutableSet<String>,
        max: Int
    ) {
        val lo = lowerBound(entries, query)
        val hi = upperBound(entries, query + "\uffff")
        var i = lo
        while (i < hi && out.size < max) {
            if (entries[i].code != query && entries[i].code.startsWith(query)) {
                out.add(entries[i].text)
            }
            i++
        }
    }

    private fun lowerBound(entries: List<Entry>, target: String): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].code >= target) {
                high = mid
            } else {
                low = mid + 1
            }
        }
        return low
    }

    private fun upperBound(entries: List<Entry>, target: String): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].code > target) {
                high = mid
            } else {
                low = mid + 1
            }
        }
        return low
    }

    companion object {
        private const val TAG = "TableImeTable"

        fun loadFromAsset(context: Context, assetPath: String): List<Entry> {
            val list = ArrayList<Entry>()
            try {
                context.assets.open(assetPath).use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                val tabIdx = trimmed.indexOf('\t')
                                if (tabIdx != -1) {
                                    val code = trimmed.substring(0, tabIdx).trim().lowercase()
                                    val text = trimmed.substring(tabIdx + 1).trim()
                                    if (code.isNotEmpty() && text.isNotEmpty()) {
                                        list.add(Entry(code, text))
                                    }
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load table from asset: $assetPath", e)
            }
            return list
        }
    }
}

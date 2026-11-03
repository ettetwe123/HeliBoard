// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object TableImeUserHistory {
    private const val TAG = "TableImeUserHistory"
    private const val KEY_PREFIX = "table_ime_freq_words_"
    private const val KEY_COUNTS_SUFFIX = "_counts"
    private const val KEY_BIGRAMS_SUFFIX = "_bigrams"

    private const val MAX_HISTORY_ENTRIES = 2000
    private const val MAX_BIGRAM_ENTRIES = 2000
    private const val DECAY_TRIGGER_COMMITS = 500
    private const val MAX_COUNT_CAP = 255

    data class WordStats(
        var count: Int,
        var lastUsed: Int // Epoch seconds
    )

    // In-memory map of word -> WordStats per table id (e.g. "cangjie")
    private val memoryCache = ConcurrentHashMap<String, ConcurrentHashMap<String, WordStats>>()

    // In-memory map of prevWord -> (nextWord -> WordStats) per table id
    private val bigramCache = ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, WordStats>>>()

    // Track which tables have dirty (unpersisted) updates
    private val dirtyTables = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var commitCountSinceDecay = 0

    fun getFrequentWordCounts(context: Context, tableId: String): Map<String, Int> {
        val statsMap = getFrequentWordStats(context, tableId)
        return statsMap.mapValues { it.value.count }
    }

    fun getFrequentWordStats(context: Context, tableId: String): ConcurrentHashMap<String, WordStats> {
        val existing = memoryCache[tableId]
        if (existing != null) return existing

        val map = ConcurrentHashMap<String, WordStats>()
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        val serialized = prefs.getString(KEY_PREFIX + tableId + KEY_COUNTS_SUFFIX, null)
        val now = (System.currentTimeMillis() / 1000).toInt()

        if (serialized != null) {
            for (line in serialized.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split('\t')
                if (parts.isNotEmpty()) {
                    val key = parts[0]
                    val count = if (parts.size >= 2) parts[1].toIntOrNull() ?: 1 else 1
                    val lastUsed = if (parts.size >= 3) parts[2].toIntOrNull() ?: now else now
                    map[key] = WordStats(minOf(MAX_COUNT_CAP, count), lastUsed)
                }
            }
        } else {
            // Backward compatibility with legacy StringSet
            val legacy = prefs.getStringSet(KEY_PREFIX + tableId, null)
            if (legacy != null) {
                for (w in legacy) {
                    map[w] = WordStats(1, now)
                }
            }
        }
        memoryCache[tableId] = map
        return map
    }

    fun getFrequentWords(context: Context, tableId: String): Set<String> {
        return getFrequentWordStats(context, tableId).keys
    }

    /**
     * Records a committed word into in-memory cache ONLY.
     * ZERO disk I/O, zero string serialization during typing.
     */
    fun recordWord(context: Context, tableId: String, word: String) {
        if (word.isEmpty()) return
        val map = getFrequentWordStats(context, tableId)
        val now = (System.currentTimeMillis() / 1000).toInt()

        map.compute(word) { _, stats ->
            if (stats == null) {
                WordStats(1, now)
            } else {
                stats.count = minOf(MAX_COUNT_CAP, stats.count + 1)
                stats.lastUsed = now
                stats
            }
        }

        dirtyTables.add(tableId)

        // Periodic aging decay check
        if (++commitCountSinceDecay >= DECAY_TRIGGER_COMMITS) {
            commitCountSinceDecay = 0
            applyDecay(map)
        }
    }

    fun getBigramStats(context: Context, tableId: String): ConcurrentHashMap<String, ConcurrentHashMap<String, WordStats>> {
        val existing = bigramCache[tableId]
        if (existing != null) return existing

        val tableMap = ConcurrentHashMap<String, ConcurrentHashMap<String, WordStats>>()
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        val serialized = prefs.getString(KEY_PREFIX + tableId + KEY_BIGRAMS_SUFFIX, null)
        val now = (System.currentTimeMillis() / 1000).toInt()

        if (serialized != null) {
            for (line in serialized.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split('\t')
                if (parts.size >= 2) {
                    val prev = parts[0]
                    val next = parts[1]
                    val count = if (parts.size >= 3) parts[2].toIntOrNull() ?: 1 else 1
                    val lastUsed = if (parts.size >= 4) parts[3].toIntOrNull() ?: now else now
                    val sub = tableMap.computeIfAbsent(prev) { ConcurrentHashMap() }
                    sub[next] = WordStats(minOf(MAX_COUNT_CAP, count), lastUsed)
                }
            }
        }
        bigramCache[tableId] = tableMap
        return tableMap
    }

    /**
     * Records a sequence of two words (prevWord -> nextWord) for dynamic prediction learning.
     * ZERO disk I/O during typing.
     */
    fun recordBigram(context: Context, tableId: String, prevWord: String, nextWord: String) {
        if (prevWord.isEmpty() || nextWord.isEmpty()) return
        val tableMap = getBigramStats(context, tableId)
        val now = (System.currentTimeMillis() / 1000).toInt()

        // Index under up to the last 2 characters of prevWord (e.g. for "香港", index under "香港" and "港")
        val keys = mutableListOf<String>()
        keys.add(prevWord)
        if (prevWord.length > 1) {
            keys.add(prevWord.takeLast(1))
        }

        for (k in keys) {
            val sub = tableMap.computeIfAbsent(k) { ConcurrentHashMap() }
            sub.compute(nextWord) { _, stats ->
                if (stats == null) {
                    WordStats(1, now)
                } else {
                    stats.count = minOf(MAX_COUNT_CAP, stats.count + 1)
                    stats.lastUsed = now
                    stats
                }
            }
        }

        dirtyTables.add(tableId)
    }

    /**
     * Looks up user-learned next-word predictions for text preceding cursor.
     * Searches from longest suffix down to single character.
     */
    fun getLearnedPredictions(context: Context, tableId: String, textBeforeCursor: String): List<String> {
        val trimmed = textBeforeCursor.trimEnd()
        if (trimmed.isEmpty()) return emptyList()
        val tableMap = getBigramStats(context, tableId)

        val candidates = LinkedHashSet<String>()
        val maxLen = minOf(4, trimmed.length)
        for (len in maxLen downTo 1) {
            val sub = trimmed.substring(trimmed.length - len)
            val subMap = tableMap[sub] ?: continue
            val sorted = subMap.entries
                .sortedWith { a, b ->
                    val cmp = b.value.count.compareTo(a.value.count)
                    if (cmp != 0) cmp else b.value.lastUsed.compareTo(a.value.lastUsed)
                }
                .map { it.key }
            candidates.addAll(sorted)
        }
        return candidates.toList()
    }

    /**
     * Periodically reduces counts to prevent old words from permanently dominating.
     */
    private fun applyDecay(map: ConcurrentHashMap<String, WordStats>) {
        for ((_, stats) in map) {
            stats.count = maxOf(1, (stats.count * 0.85).toInt())
        }
    }

    private fun applyBigramDecay(tableMap: ConcurrentHashMap<String, ConcurrentHashMap<String, WordStats>>) {
        for ((_, subMap) in tableMap) {
            for ((_, stats) in subMap) {
                stats.count = maxOf(1, (stats.count * 0.85).toInt())
            }
        }
    }

    /**
     * Flushes dirty tables to storage asynchronously on a background thread.
     * Called when the keyboard finishes input / is closed.
     */
    fun flush(context: Context, tableId: String? = null) {
        val targets = if (tableId != null) {
            if (dirtyTables.remove(tableId)) listOf(tableId) else emptyList()
        } else {
            val list = dirtyTables.toList()
            dirtyTables.clear()
            list
        }

        if (targets.isEmpty()) return

        executor.execute {
            try {
                val prefs = DeviceProtectedUtils.getSharedPreferences(context)
                for (id in targets) {
                    // 1. Flush frequent single words
                    val map = memoryCache[id]
                    if (map != null) {
                        val entriesToKeep = if (map.size > MAX_HISTORY_ENTRIES) {
                            map.entries
                                .sortedWith { a, b ->
                                    val cmp = b.value.count.compareTo(a.value.count)
                                    if (cmp != 0) cmp else b.value.lastUsed.compareTo(a.value.lastUsed)
                                }
                                .take(MAX_HISTORY_ENTRIES)
                        } else {
                            map.entries
                        }

                        if (map.size > MAX_HISTORY_ENTRIES) {
                            val keepKeys = entriesToKeep.map { it.key }.toSet()
                            map.keys.retainAll(keepKeys)
                        }

                        val serialized = entriesToKeep.joinToString("\n") {
                            "${it.key}\t${it.value.count}\t${it.value.lastUsed}"
                        }
                        prefs.edit {
                            putString(KEY_PREFIX + id + KEY_COUNTS_SUFFIX, serialized)
                        }
                    }

                    // 2. Flush learned bigrams
                    val bigrams = bigramCache[id]
                    if (bigrams != null) {
                        data class BigramRecord(val prev: String, val next: String, val stats: WordStats)
                        val allBigrams = ArrayList<BigramRecord>()
                        for ((prev, sub) in bigrams) {
                            for ((next, stats) in sub) {
                                allBigrams.add(BigramRecord(prev, next, stats))
                            }
                        }

                        val bigramsToKeep = if (allBigrams.size > MAX_BIGRAM_ENTRIES) {
                            allBigrams.sortedWith { a, b ->
                                val cmp = b.stats.count.compareTo(a.stats.count)
                                if (cmp != 0) cmp else b.stats.lastUsed.compareTo(a.stats.lastUsed)
                            }.take(MAX_BIGRAM_ENTRIES)
                        } else {
                            allBigrams
                        }

                        val serializedBigrams = bigramsToKeep.joinToString("\n") {
                            "${it.prev}\t${it.next}\t${it.stats.count}\t${it.stats.lastUsed}"
                        }
                        prefs.edit {
                            putString(KEY_PREFIX + id + KEY_BIGRAMS_SUFFIX, serializedBigrams)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush TableImeUserHistory", e)
            }
        }
    }

    fun clearFrequentWords(context: Context, tableId: String) {
        memoryCache[tableId]?.clear()
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        prefs.edit {
            remove(KEY_PREFIX + tableId)
            remove(KEY_PREFIX + tableId + KEY_COUNTS_SUFFIX)
        }
    }

    fun clearLearnedPredictions(context: Context, tableId: String) {
        bigramCache[tableId]?.clear()
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        prefs.edit {
            remove(KEY_PREFIX + tableId + KEY_BIGRAMS_SUFFIX)
        }
    }

    fun clear(context: Context, tableId: String) {
        clearFrequentWords(context, tableId)
        clearLearnedPredictions(context, tableId)
        dirtyTables.remove(tableId)
    }
}

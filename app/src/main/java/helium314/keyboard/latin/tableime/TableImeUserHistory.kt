// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import java.util.concurrent.ConcurrentHashMap

object TableImeUserHistory {
    private const val KEY_PREFIX = "table_ime_freq_words_"
    private const val KEY_COUNTS_SUFFIX = "_counts"

    // In-memory map of word -> count per table id (e.g. "cangjie5")
    private val memoryCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    fun getFrequentWordCounts(context: Context, tableId: String): Map<String, Int> {
        val existing = memoryCache[tableId]
        if (existing != null) return existing

        val map = ConcurrentHashMap<String, Int>()
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        val serialized = prefs.getString(KEY_PREFIX + tableId + KEY_COUNTS_SUFFIX, null)
        if (serialized != null) {
            for (line in serialized.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val tab = trimmed.indexOf('\t')
                if (tab > 0) {
                    val key = trimmed.substring(0, tab)
                    val count = trimmed.substring(tab + 1).toIntOrNull() ?: 1
                    map[key] = count
                }
            }
        } else {
            // Backward compatibility with legacy StringSet
            val legacy = prefs.getStringSet(KEY_PREFIX + tableId, null)
            if (legacy != null) {
                for (w in legacy) {
                    map[w] = 1
                }
            }
        }
        memoryCache[tableId] = map
        return map
    }

    fun getFrequentWords(context: Context, tableId: String): Set<String> {
        return getFrequentWordCounts(context, tableId).keys
    }

    fun recordWord(context: Context, tableId: String, word: String) {
        if (word.isEmpty()) return
        val map = (getFrequentWordCounts(context, tableId) as? ConcurrentHashMap<String, Int>)
            ?: memoryCache.getOrPut(tableId) { ConcurrentHashMap() }

        map.compute(word) { _, count -> (count ?: 0) + 1 }

        val serialized = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        prefs.edit {
            putString(KEY_PREFIX + tableId + KEY_COUNTS_SUFFIX, serialized)
        }
    }

    fun clear(context: Context, tableId: String) {
        memoryCache[tableId]?.clear()
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        prefs.edit {
            remove(KEY_PREFIX + tableId)
            remove(KEY_PREFIX + tableId + KEY_COUNTS_SUFFIX)
        }
    }
}

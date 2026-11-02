// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import java.util.concurrent.ConcurrentHashMap

object TableImeUserHistory {
    private const val KEY_PREFIX = "table_ime_freq_words_"

    // In-memory set of used characters per table id (e.g. "cangjie")
    private val memoryCache = ConcurrentHashMap<String, MutableSet<String>>()

    fun getFrequentWords(context: Context, tableId: String): Set<String> {
        val existing = memoryCache[tableId]
        if (existing != null) return existing

        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        val saved = prefs.getStringSet(KEY_PREFIX + tableId, null) ?: emptySet()
        val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(saved) }
        memoryCache[tableId] = set
        return set
    }

    fun recordWord(context: Context, tableId: String, word: String) {
        if (word.isEmpty()) return
        val set = memoryCache.getOrPut(tableId) {
            val prefs = DeviceProtectedUtils.getSharedPreferences(context)
            val saved = prefs.getStringSet(KEY_PREFIX + tableId, null) ?: emptySet()
            ConcurrentHashMap.newKeySet<String>().apply { addAll(saved) }
        }

        if (set.add(word)) {
            val prefs = DeviceProtectedUtils.getSharedPreferences(context)
            prefs.edit {
                putStringSet(KEY_PREFIX + tableId, HashSet(set))
            }
        }
    }

    fun clear(context: Context, tableId: String) {
        memoryCache[tableId]?.clear()
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        prefs.edit {
            remove(KEY_PREFIX + tableId)
        }
    }
}

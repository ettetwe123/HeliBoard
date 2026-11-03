// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object TableImeRegistry {
    private const val TAG = "TableImeRegistry"
    private val executor = Executors.newSingleThreadExecutor()

    private val specs = ConcurrentHashMap<String, TableImeSpec>().apply {
        put(CangjieSpec.combiningRules, CangjieSpec)
    }

    private val tables = ConcurrentHashMap<String, TableImeTable>()
    private val wordPredictors = ConcurrentHashMap<String, TableImeWordPredictor>()

    @Volatile
    var activeCombiner: TableImeCombiner? = null

    fun getSpec(combiningRules: String?): TableImeSpec? =
        if (combiningRules == null) null else specs[combiningRules]

    fun isTableIme(combiningRules: String?): Boolean =
        getSpec(combiningRules) != null

    fun isTableImeLocale(locale: java.util.Locale): Boolean =
        locale.language == "zh" && (locale.country.equals("HK", ignoreCase = true) || locale.country.equals("TW", ignoreCase = true))

    fun getTable(context: Context, combiningRules: String): TableImeTable? {
        val spec = getSpec(combiningRules) ?: return null
        val existing = tables[spec.id]
        if (existing != null) return existing

        // Load synchronously if needed on first query
        synchronized(tables) {
            tables[spec.id]?.let { return it }
            val table = TableImeTable.loadFromAsset(context, spec.baseTableAsset)
            tables[spec.id] = table
            return table
        }
    }

    fun getWordPredictor(context: Context, combiningRules: String): TableImeWordPredictor? {
        val spec = getSpec(combiningRules) ?: return null
        val asset = spec.nextWordsAsset ?: return null
        val existing = wordPredictors[spec.id]
        if (existing != null) return existing

        synchronized(wordPredictors) {
            wordPredictors[spec.id]?.let { return it }
            val predictor = TableImeWordPredictor.loadFromAsset(context, asset)
            wordPredictors[spec.id] = predictor
            return predictor
        }
    }

    fun preloadAsync(context: Context, combiningRules: String, loadExtended: Boolean = false) {
        val spec = getSpec(combiningRules) ?: return
        executor.execute {
            try {
                val table = getTable(context, combiningRules)
                if (loadExtended && spec.extendedTableAsset != null && table != null && table.extendedSource == null) {
                    val ext = TableImeTable.loadSourceFromAsset(context, spec.extendedTableAsset!!)
                    table.extendedSource = ext
                    Log.i(TAG, "Loaded extended table for ${spec.id}")
                }
                if (spec.nextWordsAsset != null && !wordPredictors.containsKey(spec.id)) {
                    getWordPredictor(context, combiningRules)
                    Log.i(TAG, "Loaded word predictor for ${spec.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed preloading table for $combiningRules", e)
            }
        }
    }

    fun ensureExtendedLoaded(context: Context, combiningRules: String) {
        val spec = getSpec(combiningRules) ?: return
        val extAsset = spec.extendedTableAsset ?: return
        val table = tables[spec.id] ?: getTable(context, combiningRules) ?: return
        if (table.extendedSource == null) {
            executor.execute {
                synchronized(table) {
                    if (table.extendedSource == null) {
                        val ext = TableImeTable.loadSourceFromAsset(context, extAsset)
                        table.extendedSource = ext
                        Log.i(TAG, "Loaded extended table on demand for ${spec.id}")
                    }
                }
            }
        }
    }
}

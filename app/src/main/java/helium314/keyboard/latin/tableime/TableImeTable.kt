// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TableImeTable(
    val baseSource: Source,
    @Volatile var extendedSource: Source? = null
) {
    data class Entry(val code: String, val text: String)

    interface Source {
        fun collectExactMatches(query: String, out: MutableSet<String>, max: Int)
        fun collectPrefixMatches(query: String, out: MutableSet<String>, max: Int)
    }

    // Secondary constructors for backward compatibility with List<Entry>
    constructor(
        baseEntries: List<Entry>,
        extendedEntries: List<Entry>? = null
    ) : this(ListSource(baseEntries), extendedEntries?.let { ListSource(it) })

    val baseEntries: List<Entry>
        get() = (baseSource as? ListSource)?.entries ?: emptyList()

    var extendedEntries: List<Entry>?
        get() = (extendedSource as? ListSource)?.entries
        set(value) {
            extendedSource = value?.let { ListSource(it) }
        }

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
        baseSource.collectExactMatches(query, exactMatches, maxCandidates)

        // 2. Exact matches from extended (if enabled and loaded)
        val ext = if (includeExtended) extendedSource else null
        if (ext != null && exactMatches.size < maxCandidates) {
            ext.collectExactMatches(query, exactMatches, maxCandidates)
        }

        val prefixMatches = LinkedHashSet<String>()

        // 3. Prefix matches from base
        if (exactMatches.size + prefixMatches.size < maxCandidates) {
            baseSource.collectPrefixMatches(query, prefixMatches, maxCandidates - exactMatches.size)
        }

        // 4. Prefix matches from extended
        if (ext != null && exactMatches.size + prefixMatches.size < maxCandidates) {
            ext.collectPrefixMatches(query, prefixMatches, maxCandidates - exactMatches.size)
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

    class ListSource(val entries: List<Entry>) : Source {
        override fun collectExactMatches(query: String, out: MutableSet<String>, max: Int) {
            val lo = lowerBound(entries, query)
            var i = lo
            while (i < entries.size && entries[i].code == query && out.size < max) {
                out.add(entries[i].text)
                i++
            }
        }

        override fun collectPrefixMatches(query: String, out: MutableSet<String>, max: Int) {
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
    }

    class BinarySource(private val buffer: ByteBuffer) : Source {
        private val entryCount: Int
        private val dataOffset: Int

        init {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.getInt(0)
            require(magic == MAGIC_CJTB) { "Invalid CJTB magic: 0x${Integer.toHexString(magic)}" }
            entryCount = buffer.getInt(8)
            dataOffset = buffer.getInt(12)
        }

        override fun collectExactMatches(query: String, out: MutableSet<String>, max: Int) {
            val lo = lowerBound(query)
            if (lo < entryCount && compareCode(lo, query) == 0) {
                getCandidates(lo, out, max)
            }
        }

        override fun collectPrefixMatches(query: String, out: MutableSet<String>, max: Int) {
            val lo = lowerBound(query)
            var i = lo
            if (i < entryCount && compareCode(i, query) == 0) {
                i++
            }
            while (i < entryCount && out.size < max && codeStartsWith(i, query)) {
                getCandidates(i, out, max)
                i++
            }
        }

        private fun compareCode(index: Int, target: String): Int {
            val offset = 16 + (index shl 4)
            val tLen = target.length
            for (j in 0 until 8) {
                val b = buffer.get(offset + j).toInt() and 0xFF
                if (b == 0) {
                    return if (j == tLen) 0 else -1
                }
                if (j >= tLen) {
                    return 1
                }
                val tc = target[j].code
                if (b != tc) {
                    return b - tc
                }
            }
            return if (8 == tLen) 0 else 1
        }

        private fun codeStartsWith(index: Int, prefix: String): Boolean {
            val offset = 16 + (index shl 4)
            val pLen = prefix.length
            if (pLen > 8) return false
            for (j in 0 until pLen) {
                val b = buffer.get(offset + j).toInt() and 0xFF
                if (b == 0 || b != prefix[j].code) return false
            }
            return true
        }

        private fun lowerBound(target: String): Int {
            var low = 0
            var high = entryCount
            while (low < high) {
                val mid = (low + high) ushr 1
                if (compareCode(mid, target) >= 0) {
                    high = mid
                } else {
                    low = mid + 1
                }
            }
            return low
        }

        private fun getCandidates(index: Int, out: MutableSet<String>, max: Int) {
            val idxOffset = 16 + (index shl 4)
            val candOffset = buffer.getInt(idxOffset + 8)
            val count = buffer.getShort(idxOffset + 12).toInt() and 0xFFFF

            var pos = dataOffset + candOffset
            for (c in 0 until count) {
                if (out.size >= max) break
                val len = buffer.get(pos).toInt() and 0xFF
                pos++
                if (buffer.hasArray()) {
                    val array = buffer.array()
                    val offset = buffer.arrayOffset() + pos
                    out.add(String(array, offset, len, Charsets.UTF_8))
                } else {
                    val bytes = ByteArray(len)
                    val oldPos = buffer.position()
                    buffer.position(pos)
                    buffer.get(bytes)
                    buffer.position(oldPos)
                    out.add(String(bytes, Charsets.UTF_8))
                }
                pos += len
            }
        }
    }

    companion object {
        private const val TAG = "TableImeTable"
        private const val MAGIC_CJTB = 0x42544A43 // "CJTB" in little endian

        fun loadSourceFromAsset(context: Context, assetPath: String): Source? {
            try {
                // Try .bin first if not explicitly named
                val resolvedPath = if (!assetPath.endsWith(".bin") && !assetPath.endsWith(".tsv")) {
                    "$assetPath.bin"
                } else {
                    assetPath
                }

                val input: InputStream = try {
                    context.assets.open(resolvedPath)
                } catch (e: Exception) {
                    if (resolvedPath.endsWith(".bin")) {
                        // Fallback to .tsv
                        context.assets.open(resolvedPath.removeSuffix(".bin") + ".tsv")
                    } else {
                        throw e
                    }
                }

                input.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size >= 16 &&
                        bytes[0] == 'C'.code.toByte() &&
                        bytes[1] == 'J'.code.toByte() &&
                        bytes[2] == 'T'.code.toByte() &&
                        bytes[3] == 'B'.code.toByte()
                    ) {
                        return BinarySource(ByteBuffer.wrap(bytes))
                    } else {
                        // Fallback to TSV parser
                        return ListSource(parseTsv(bytes))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load table source from asset: $assetPath", e)
            }
            return null
        }

        fun loadFromAsset(context: Context, assetPath: String): TableImeTable {
            val source = loadSourceFromAsset(context, assetPath) ?: ListSource(emptyList())
            return TableImeTable(source)
        }

        private fun parseTsv(bytes: ByteArray): List<Entry> {
            val list = ArrayList<Entry>()
            BufferedReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8)).use { reader ->
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
            return list
        }
    }
}

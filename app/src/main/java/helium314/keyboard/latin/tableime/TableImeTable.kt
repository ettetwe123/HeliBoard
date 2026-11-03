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
    @Volatile var extendedSource: Source? = null,
    var spec: TableImeSpec? = null
) {
    data class Entry(val code: String, val text: String)

    interface Source {
        fun collectExactMatches(query: String, out: MutableSet<String>, max: Int)
        fun collectPrefixMatches(query: String, out: MutableSet<String>, max: Int)
    }

    // Secondary constructors for backward compatibility with List<Entry>
    constructor(
        baseEntries: List<Entry>,
        extendedEntries: List<Entry>? = null,
        spec: TableImeSpec? = null
    ) : this(ListSource(baseEntries), extendedEntries?.let { ListSource(it) }, spec)

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
        frequentWordCounts: Map<String, Int>? = null,
        enableFuzzy: Boolean = true,
        isCommonWord: ((String) -> Boolean)? = null,
        overrideSpec: TableImeSpec? = null
    ): List<String> {
        if (code.isEmpty()) return emptyList()
        val query = code.lowercase()
        val baseExact = LinkedHashSet<String>()
        val basePrefix = LinkedHashSet<String>()
        val extExact = LinkedHashSet<String>()
        val extPrefix = LinkedHashSet<String>()

        val activeSpec = overrideSpec ?: spec
        val fuzzyPolicy = activeSpec?.fuzzyPolicy ?: TableImeFuzzyPolicy.DEFAULT
        val rankingPolicy = activeSpec?.rankingPolicy ?: TableImeRankingPolicy.DEFAULT

        // 1. Exact matches from base
        baseSource.collectExactMatches(query, baseExact, maxCandidates)

        // 2. Prefix matches from base (common words always come before extended rare characters)
        if (rankingPolicy.allowPrefixMatching && baseExact.size < maxCandidates) {
            baseSource.collectPrefixMatches(query, basePrefix, maxCandidates - baseExact.size)
        }

        // 3. Extended matches (if enabled and loaded, placed after base entries)
        val ext = if (includeExtended) extendedSource else null
        if (ext != null) {
            val remaining = maxCandidates - baseExact.size - basePrefix.size
            if (remaining > 0) {
                ext.collectExactMatches(query, extExact, remaining)
            }
            if (rankingPolicy.allowPrefixMatching) {
                val remainingAfterExact = maxCandidates - baseExact.size - basePrefix.size - extExact.size
                if (remainingAfterExact > 0) {
                    ext.collectPrefixMatches(query, extPrefix, remainingAfterExact)
                }
            }
        }

        val directExactTotal = baseExact.size + extExact.size
        val directTotal = baseExact.size + basePrefix.size + extExact.size + extPrefix.size
        val hasCommonDirect = isCommonWord != null && (baseExact.any(isCommonWord) || extExact.any(isCommonWord))

        val fuzzyMatches = LinkedHashSet<String>()
        // Only trigger fuzzy when enabled in policy and parameter, length >= minCodeLength,
        // and direct suggestions are fewer than directMatchThreshold
        // and either no exact matches or no common exact matches exist
        if (enableFuzzy && fuzzyPolicy.enabled && query.length >= fuzzyPolicy.minCodeLength && directTotal < fuzzyPolicy.directMatchThreshold && (directExactTotal == 0 || !hasCommonDirect)) {
            val fuzzyRaw = LinkedHashSet<String>()
            collectFuzzyExactMatches(
                query,
                fuzzyRaw,
                maxCandidates,
                includeExtended,
                allowDeletion = fuzzyPolicy.allowExtraKeyDeletion && basePrefix.isEmpty(),
                neighborMap = fuzzyPolicy.neighborKeys
            )
            for (w in fuzzyRaw) {
                if (!baseExact.contains(w) && !basePrefix.contains(w) && !extExact.contains(w) && !extPrefix.contains(w)) {
                    fuzzyMatches.add(w)
                }
            }
        }

        if (frequentWordCounts.isNullOrEmpty()) {
            val candidates = ArrayList<String>(baseExact.size + basePrefix.size + extExact.size + extPrefix.size + fuzzyMatches.size)
            if (directExactTotal == 0 && basePrefix.isEmpty() && extPrefix.isEmpty()) {
                if (isCommonWord != null) {
                    val commonFuzzy = ArrayList<String>()
                    val remainingFuzzy = ArrayList<String>()
                    for (w in fuzzyMatches) {
                        if (isCommonWord(w)) commonFuzzy.add(w) else remainingFuzzy.add(w)
                    }
                    candidates.addAll(commonFuzzy)
                    candidates.addAll(remainingFuzzy)
                } else {
                    candidates.addAll(fuzzyMatches)
                }
                return candidates
            }

            candidates.addAll(baseExact)
            candidates.addAll(basePrefix)
            if (isCommonWord != null && fuzzyMatches.isNotEmpty()) {
                val commonFuzzy = ArrayList<String>()
                val remainingFuzzy = ArrayList<String>()
                for (w in fuzzyMatches) {
                    if (isCommonWord(w)) commonFuzzy.add(w) else remainingFuzzy.add(w)
                }
                candidates.addAll(commonFuzzy)
                candidates.addAll(extExact)
                candidates.addAll(extPrefix)
                candidates.addAll(remainingFuzzy)
            } else {
                candidates.addAll(extExact)
                candidates.addAll(extPrefix)
                candidates.addAll(fuzzyMatches)
            }
            return candidates
        }

        // Option A: Base exact -> Base prefix -> Ext exact -> Ext prefix
        // (within each section, frequent words sorted by count DESC, then non-frequent in table order.
        // For exact matches, used words retain stable table order to avoid homocode leapfrog oscillation).
        val result = ArrayList<String>(baseExact.size + basePrefix.size + extExact.size + extPrefix.size + fuzzyMatches.size)

        fun appendPartition(matches: Iterable<String>, isExact: Boolean = false) {
            val frequentList = ArrayList<String>()
            val nonFrequentList = ArrayList<String>()
            for (word in matches) {
                if ((frequentWordCounts[word] ?: 0) > 0) {
                    frequentList.add(word)
                } else {
                    nonFrequentList.add(word)
                }
            }
            val shouldSortByCount = !isExact || !rankingPolicy.preserveHomocodeTableOrder
            if (shouldSortByCount) {
                frequentList.sortWith { a, b ->
                    val ca = frequentWordCounts[a] ?: 0
                    val cb = frequentWordCounts[b] ?: 0
                    cb.compareTo(ca)
                }
            }
            result.addAll(frequentList)
            result.addAll(nonFrequentList)
        }

        if (directExactTotal == 0 && basePrefix.isEmpty() && extPrefix.isEmpty()) {
            if (isCommonWord != null) {
                val commonFuzzy = ArrayList<String>()
                val remainingFuzzy = ArrayList<String>()
                for (w in fuzzyMatches) {
                    if (isCommonWord(w)) commonFuzzy.add(w) else remainingFuzzy.add(w)
                }
                appendPartition(commonFuzzy, isExact = false)
                appendPartition(remainingFuzzy, isExact = false)
            } else {
                appendPartition(fuzzyMatches, isExact = false)
            }
            return result
        }

        appendPartition(baseExact, isExact = true)
        appendPartition(basePrefix, isExact = false)
        if (isCommonWord != null && fuzzyMatches.isNotEmpty()) {
            val commonFuzzy = ArrayList<String>()
            val remainingFuzzy = ArrayList<String>()
            for (w in fuzzyMatches) {
                if (isCommonWord(w)) commonFuzzy.add(w) else remainingFuzzy.add(w)
            }
            appendPartition(commonFuzzy, isExact = false)
            appendPartition(extExact, isExact = true)
            appendPartition(extPrefix, isExact = false)
            appendPartition(remainingFuzzy, isExact = false)
        } else {
            appendPartition(extExact, isExact = true)
            appendPartition(extPrefix, isExact = false)
            appendPartition(fuzzyMatches, isExact = false)
        }
        return result
    }

    fun collectFuzzyExactMatches(
        query: String,
        out: MutableSet<String>,
        max: Int,
        includeExtended: Boolean = false,
        allowDeletion: Boolean = true,
        neighborMap: Map<Char, List<Char>> = emptyMap()
    ) {
        if (query.length < 3) return
        val seenCodes = HashSet<String>()
        seenCodes.add(query)

        val ext = if (includeExtended) extendedSource else null

        fun tryCode(variant: String) {
            if (out.size >= max || !seenCodes.add(variant)) return
            baseSource.collectExactMatches(variant, out, max)
            if (ext != null && out.size < max) {
                ext.collectExactMatches(variant, out, max)
            }
        }

        // 1. First-key neighbor substitution (highest priority for neighbor typos like 'cnd' -> 'vnd' -> 好)
        if (neighborMap.isNotEmpty()) {
            val firstChar = query[0]
            val firstNeighbors = neighborMap[firstChar]
            if (firstNeighbors != null) {
                val tail = query.substring(1)
                for (nb in firstNeighbors) {
                    tryCode(nb + tail)
                }
            }
        }

        // 2. Extra key deletion (drop 1 char, for typos like 'gnrmi' -> drop 'r' -> 'gnmi' -> 壽)
        // Only run when allowDeletion is true (e.g. when basePrefix is empty)
        if (allowDeletion) {
            for (i in 0 until query.length) {
                val sub = query.removeRange(i, i + 1)
                if (sub.length >= 2) {
                    tryCode(sub)
                }
            }
        }

        // 3. Other positions neighbor substitution
        if (neighborMap.isNotEmpty()) {
            for (i in 1 until query.length) {
                val ch = query[i]
                val neighbors = neighborMap[ch] ?: continue
                val prefix = query.substring(0, i)
                val suffix = query.substring(i + 1)
                for (nb in neighbors) {
                    tryCode(prefix + nb + suffix)
                }
            }
        }
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

        @JvmOverloads
        fun loadFromAsset(context: Context, assetPath: String, spec: TableImeSpec? = null): TableImeTable {
            val source = loadSourceFromAsset(context, assetPath) ?: ListSource(emptyList())
            return TableImeTable(source, spec = spec)
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

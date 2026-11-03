// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TableImeWordPredictor(
    val source: Source
) {
    interface Source {
        fun lookupContinuations(
            prefix: String,
            maxCandidates: Int = 500,
            frequentWordCounts: Map<String, Int>? = null
        ): List<String>
        fun hasWordsStartingWith(prefix: String): Boolean
        val size: Int
    }

    // Secondary constructor for backward compatibility with List<String>
    constructor(words: List<String>) : this(ListSource(words))

    val words: List<String>
        get() = (source as? ListSource)?.words ?: emptyList()

    fun isCommon(text: String): Boolean {
        return source.hasWordsStartingWith(text)
    }

    /**
     * Given text before the cursor, predicts the next continuation words/characters.
     * Searches with longest suffix of [textBeforeCursor] first (e.g. 4, 3, 2, 1 characters).
     * Candidates are continuations (e.g. for prefix "香", word "香港" yields "港").
     * Candidates are deduplicated and sorted by frequency count DESC if frequent,
     * then by continuation length (shorter first).
     */
    fun predict(
        textBeforeCursor: String,
        maxCandidates: Int = 500,
        frequentWordCounts: Map<String, Int>? = null
    ): List<String> {
        if (textBeforeCursor.isEmpty() || source.size == 0) return emptyList()

        val trimmed = textBeforeCursor.trimEnd()
        if (trimmed.isEmpty()) return emptyList()

        // Try prefixes from longest to shortest (up to 4 chars)
        val maxPrefixLen = minOf(4, trimmed.length)
        for (len in maxPrefixLen downTo 1) {
            val prefix = trimmed.substring(trimmed.length - len)
            val candidates = source.lookupContinuations(prefix, maxCandidates, frequentWordCounts)
            if (candidates.isNotEmpty()) {
                return candidates
            }
        }
        return emptyList()
    }

    class ListSource(val words: List<String>) : Source {
        override val size: Int get() = words.size

        override fun hasWordsStartingWith(prefix: String): Boolean {
            if (prefix.isEmpty() || words.isEmpty()) return false
            val lo = lowerBound(words, prefix)
            return lo < words.size && words[lo].startsWith(prefix)
        }

        override fun lookupContinuations(
            prefix: String,
            maxCandidates: Int,
            frequentWordCounts: Map<String, Int>?
        ): List<String> {
            if (prefix.isEmpty() || words.isEmpty()) return emptyList()

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

            val rawContinuations = ArrayList<String>()
            val seen = HashSet<String>()
            for (w in matches) {
                val continuation = w.substring(prefix.length)
                if (continuation.isNotEmpty() && seen.add(continuation)) {
                    rawContinuations.add(continuation)
                }
            }

            return rankCandidates(rawContinuations, maxCandidates, frequentWordCounts)
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
    }

    class BinarySource(private val buffer: ByteBuffer) : Source {
        private val wordCount: Int
        private val dataOffset: Int

        init {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.getInt(0)
            require(magic == MAGIC_CJWP) { "Invalid CJWP magic: 0x${Integer.toHexString(magic)}" }
            wordCount = buffer.getInt(8)
            dataOffset = buffer.getInt(12)
        }

        override val size: Int get() = wordCount

        override fun hasWordsStartingWith(prefix: String): Boolean {
            if (prefix.isEmpty() || wordCount == 0) return false
            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            val lo = lowerBound(prefixBytes)
            return lo < wordCount && wordStartsWith(lo, prefixBytes)
        }

        override fun lookupContinuations(
            prefix: String,
            maxCandidates: Int,
            frequentWordCounts: Map<String, Int>?
        ): List<String> {
            if (prefix.isEmpty() || wordCount == 0) return emptyList()

            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            val lo = lowerBound(prefixBytes)
            if (lo >= wordCount) return emptyList()

            val matches = ArrayList<String>()
            var i = lo
            while (i < wordCount && wordStartsWith(i, prefixBytes)) {
                val w = getWord(i)
                if (w.length > prefix.length) {
                    matches.add(w)
                }
                i++
            }

            if (matches.isEmpty()) return emptyList()

            matches.sortWith { a, b ->
                val diff = (a.length - prefix.length).compareTo(b.length - prefix.length)
                if (diff != 0) diff else a.compareTo(b)
            }

            val rawContinuations = ArrayList<String>()
            val seen = HashSet<String>()
            for (w in matches) {
                val continuation = w.substring(prefix.length)
                if (continuation.isNotEmpty() && seen.add(continuation)) {
                    rawContinuations.add(continuation)
                }
            }

            return rankCandidates(rawContinuations, maxCandidates, frequentWordCounts)
        }

        private fun getWord(index: Int): String {
            val off = buffer.getInt(16 + (index shl 2))
            val pos = dataOffset + off
            val len = buffer.get(pos).toInt() and 0xFF
            val start = pos + 1
            return if (buffer.hasArray()) {
                val array = buffer.array()
                val offset = buffer.arrayOffset() + start
                String(array, offset, len, Charsets.UTF_8)
            } else {
                val bytes = ByteArray(len)
                val oldPos = buffer.position()
                buffer.position(start)
                buffer.get(bytes)
                buffer.position(oldPos)
                String(bytes, Charsets.UTF_8)
            }
        }

        private fun compareWord(index: Int, targetBytes: ByteArray): Int {
            val off = buffer.getInt(16 + (index shl 2))
            val pos = dataOffset + off
            val len = buffer.get(pos).toInt() and 0xFF
            val minLen = minOf(len, targetBytes.size)
            for (j in 0 until minLen) {
                val b = buffer.get(pos + 1 + j).toInt() and 0xFF
                val tb = targetBytes[j].toInt() and 0xFF
                if (b != tb) return b - tb
            }
            return len - targetBytes.size
        }

        private fun wordStartsWith(index: Int, prefixBytes: ByteArray): Boolean {
            val off = buffer.getInt(16 + (index shl 2))
            val pos = dataOffset + off
            val len = buffer.get(pos).toInt() and 0xFF
            if (len < prefixBytes.size) return false
            for (j in prefixBytes.indices) {
                val b = buffer.get(pos + 1 + j).toInt() and 0xFF
                val pb = prefixBytes[j].toInt() and 0xFF
                if (b != pb) return false
            }
            return true
        }

        private fun lowerBound(prefixBytes: ByteArray): Int {
            var low = 0
            var high = wordCount
            while (low < high) {
                val mid = (low + high) ushr 1
                if (compareWord(mid, prefixBytes) >= 0) {
                    high = mid
                } else {
                    low = mid + 1
                }
            }
            return low
        }
    }

    companion object {
        private const val TAG = "TableImeWordPredictor"
        private const val MAGIC_CJWP = 0x50574A43 // "CJWP" in little endian

        private fun rankCandidates(
            rawContinuations: List<String>,
            maxCandidates: Int,
            frequentWordCounts: Map<String, Int>?
        ): List<String> {
            if (frequentWordCounts.isNullOrEmpty()) {
                return if (rawContinuations.size > maxCandidates) rawContinuations.subList(0, maxCandidates) else rawContinuations
            }

            val frequentList = ArrayList<String>()
            val nonFrequentList = ArrayList<String>()
            for (c in rawContinuations) {
                if ((frequentWordCounts[c] ?: 0) > 0) {
                    frequentList.add(c)
                } else {
                    nonFrequentList.add(c)
                }
            }
            frequentList.sortWith { a, b ->
                val ca = frequentWordCounts[a] ?: 0
                val cb = frequentWordCounts[b] ?: 0
                cb.compareTo(ca)
            }

            val results = ArrayList<String>(minOf(frequentList.size + nonFrequentList.size, maxCandidates))
            for (w in frequentList) {
                if (results.size >= maxCandidates) break
                results.add(w)
            }
            for (w in nonFrequentList) {
                if (results.size >= maxCandidates) break
                results.add(w)
            }
            return results
        }

        fun loadFromAsset(context: Context, assetPath: String): TableImeWordPredictor {
            try {
                val resolvedPath = if (!assetPath.endsWith(".bin") && !assetPath.endsWith(".tsv")) {
                    "$assetPath.bin"
                } else {
                    assetPath
                }

                val input: InputStream = try {
                    context.assets.open(resolvedPath)
                } catch (e: Exception) {
                    if (resolvedPath.endsWith(".bin")) {
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
                        bytes[2] == 'W'.code.toByte() &&
                        bytes[3] == 'P'.code.toByte()
                    ) {
                        return TableImeWordPredictor(BinarySource(ByteBuffer.wrap(bytes)))
                    } else {
                        return TableImeWordPredictor(ListSource(parseWordsTsv(bytes)))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load words from asset: $assetPath", e)
            }
            return TableImeWordPredictor(emptyList())
        }

        private fun parseWordsTsv(bytes: ByteArray): List<String> {
            val words = ArrayList<String>()
            BufferedReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8)).use { reader ->
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
            return words
        }
    }
}

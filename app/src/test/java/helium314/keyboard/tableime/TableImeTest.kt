// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tableime

import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.tableime.CangjieSpec
import helium314.keyboard.latin.tableime.TableImeCombiner
import helium314.keyboard.latin.tableime.TableImeRegistry
import helium314.keyboard.latin.tableime.TableImeTable
import helium314.keyboard.latin.tableime.TableImeUserHistory
import helium314.keyboard.latin.tableime.TableImeWordPredictor
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TableImeTest {

    @Test
    fun testCangjieRadicals() {
        assertEquals("日", CangjieSpec.codeToRadical('a'))
        assertEquals("月", CangjieSpec.codeToRadical('b'))
        assertEquals("日月", CangjieSpec.getRadicalString("ab"))
        assertEquals("手田水口廿卜山戈人心", CangjieSpec.getRadicalString("qwertyuiop"))
        assertEquals("卜卜一口", CangjieSpec.getRadicalString("yymr"))
    }

    @Test
    fun testTableLookup() {
        val baseEntries = listOf(
            TableImeTable.Entry("a", "日"),
            TableImeTable.Entry("a", "曰"),
            TableImeTable.Entry("aa", "昌"),
            TableImeTable.Entry("aaa", "晶"),
            TableImeTable.Entry("ab", "明"),
            TableImeTable.Entry("b", "月"),
            TableImeTable.Entry("d", "木"),
            TableImeTable.Entry("dd", "林"),
            TableImeTable.Entry("ddd", "森")
        )
        val extEntries = listOf(
            TableImeTable.Entry("aa", "昍"),
            TableImeTable.Entry("ab", "冐")
        )

        val table = TableImeTable(baseEntries, extEntries)

        // Exact match 'a' without extended
        val resA = table.lookup("a", includeExtended = false)
        assertEquals("日", resA[0])
        assertEquals("曰", resA[1])
        assertTrue(resA.contains("昌"))
        assertTrue(resA.contains("晶"))
        assertTrue(resA.contains("明"))
        assertFalse(resA.contains("昍"))

        // Exact match 'a' with extended
        val resAExt = table.lookup("a", includeExtended = true)
        assertTrue(resAExt.contains("昍"))

        // Exact match 'ab'
        val resAB = table.lookup("ab", includeExtended = true)
        assertEquals("明", resAB[0])
        assertEquals("冐", resAB[1])

        // Exact match 'd'
        val resD = table.lookup("d")
        assertEquals("木", resD[0])
        assertTrue(resD.contains("林"))
        assertTrue(resD.contains("森"))

        // Common base prefix matches must precede obscure extended exact matches (e.g. '好' before '𠃑')
        val testTable = TableImeTable(
            listOf(TableImeTable.Entry("vnd", "好")),
            listOf(TableImeTable.Entry("vn", "𠃑"))
        )
        val resVN = testTable.lookup("vn", includeExtended = true)
        assertEquals("好", resVN[0])
        assertEquals("𠃑", resVN[1])
    }

    @Test
    fun testCombinerLifecycle() {
        val combiner = TableImeCombiner(CangjieSpec)
        assertFalse(combiner.isComposing)
        assertEquals("", combiner.rawCode)

        // Type 'a'
        val evA = Event.createSoftwareKeypressEvent('a'.code, 'a'.code, 0, 0, 0, false)
        val procA = combiner.processEvent(null, evA)
        assertTrue(procA.isConsumed)
        assertTrue(combiner.isComposing)
        assertEquals("a", combiner.rawCode)
        assertEquals("日", combiner.combiningStateFeedback.toString())

        // Type 'b'
        val evB = Event.createSoftwareKeypressEvent('b'.code, 'b'.code, 0, 0, 0, false)
        val procB = combiner.processEvent(null, evB)
        assertTrue(procB.isConsumed)
        assertEquals("ab", combiner.rawCode)
        assertEquals("日月", combiner.combiningStateFeedback.toString())

        // Backspace 'b'
        val evDel = Event.createSoftwareKeypressEvent(Constants.NOT_A_CODE, KeyCode.DELETE, 0, 0, 0, false)
        val procDel = combiner.processEvent(null, evDel)
        assertTrue(procDel.isConsumed)
        assertEquals("a", combiner.rawCode)
        assertEquals("日", combiner.combiningStateFeedback.toString())

        // Backspace 'a' -> should reset and exit
        val procDel2 = combiner.processEvent(null, evDel)
        assertFalse(combiner.isComposing)
        assertEquals("", combiner.rawCode)

        // Non-letter (e.g. space) should pass through
        val evSpace = Event.createSoftwareKeypressEvent(' '.code, Constants.CODE_SPACE, 0, 0, 0, false)
        val procSpace = combiner.processEvent(null, evSpace)
        assertFalse(procSpace.isConsumed)
    }

    @Test
    fun testRegistry() {
        assertTrue(TableImeRegistry.isTableIme("cangjie"))
        assertFalse(TableImeRegistry.isTableIme("hangul"))
        assertFalse(TableImeRegistry.isTableIme("unknown"))
        assertEquals(CangjieSpec, TableImeRegistry.getSpec("cangjie"))
    }

    @Test
    fun testParseCangjieLayout() {
        val json = java.io.File("src/main/assets/layouts/main/cangjie.json").readText()
        val parsed = helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser.parseJsonString(json, false)
        println("First key: ${parsed[0][0]}")
    }

    @Test
    fun testLookupRealAsset() {
        val file = java.io.File("src/main/assets/tableime/cangjie_base.tsv")
        val entries = file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab != -1) TableImeTable.Entry(line.substring(0, tab).trim().lowercase(), line.substring(tab + 1).trim()) else null
        }
        val table = TableImeTable(entries)
        val resJmso = table.lookup("jmso")
        println("Lookup jmso: $resJmso")
        assertTrue(resJmso.contains("家"))
        assertEquals("家", resJmso[0])
        val resVnd = table.lookup("vnd")
        println("Lookup vnd: $resVnd")
        assertTrue(resVnd.contains("好"))
        assertEquals("好", resVnd[0])
        val resKokr = table.lookup("kokr")
        println("Lookup kokr: $resKokr")
        assertTrue(resKokr.contains("痴"))
        assertEquals("痴", resKokr[0])
    }

    @Test
    fun testTableImeSuggestedWordsSingleCandidate() {
        val list = arrayListOf(
            helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo(
                "痴", "", 1000, helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo.KIND_CORRECTION,
                helium314.keyboard.latin.dictionary.Dictionary.DICTIONARY_USER_TYPED, 0, -1
            )
        )
        val tableSuggestedWords = helium314.keyboard.latin.tableime.TableImeSuggestedWords(list, 1)
        assertEquals(1, tableSuggestedWords.size())
        assertEquals(1, tableSuggestedWords.wordCountToShow)
        // Ensure auto-correction candidate is index 0 and doesn't crash on size 1!
        assertEquals("痴", tableSuggestedWords.autoCorrectionWord)
        assertEquals("痴", tableSuggestedWords.autoCorrectionWordInfo?.mWord)
        assertEquals("痴", tableSuggestedWords.autoCommitCandidate?.mWord)
    }

    @Test
    fun testTableImeSuggestedWordsFlags() {
        val emptyNormal = helium314.keyboard.latin.SuggestedWords.getEmptyInstance(false)
        assertFalse(emptyNormal.mIsTableIme)
        val emptyTable = helium314.keyboard.latin.SuggestedWords.getEmptyInstance(true)
        assertTrue(emptyTable.mIsTableIme)
    }

    @Test
    fun testTableImeHandlerDecoupling() {
        assertTrue(helium314.keyboard.latin.tableime.TableImeHandler.isTableIme("cangjie"))
        assertFalse(helium314.keyboard.latin.tableime.TableImeHandler.isTableIme("bn_khipro"))
        assertFalse(helium314.keyboard.latin.tableime.TableImeHandler.isTableIme(null))

        assertFalse(helium314.keyboard.latin.tableime.TableImeHandler.shouldAutoSpace("cangjie"))
        assertTrue(helium314.keyboard.latin.tableime.TableImeHandler.shouldAutoSpace("en"))
        assertTrue(helium314.keyboard.latin.tableime.TableImeHandler.shouldAutoSpace(null))

        val list = arrayListOf(
            helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo(
                "痴", "", 1000, helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo.KIND_CORRECTION,
                helium314.keyboard.latin.dictionary.Dictionary.DICTIONARY_USER_TYPED, 0, -1
            )
        )
        val tableSuggestedWords = helium314.keyboard.latin.tableime.TableImeSuggestedWords(list, 1)
        assertTrue(helium314.keyboard.latin.tableime.TableImeHandler.shouldUpdateSuggestions(tableSuggestedWords, "cangjie"))
    }

    @Test
    fun testWordPredictor() {
        val words = listOf(
            "北京",
            "北京大學",
            "北極",
            "北美",
            "香港",
            "香港人",
            "香港島",
            "香水",
            "香蕉",
            "食飯",
            "食飯未",
            "食物",
            "食嘢"
        ).sorted()

        val predictor = helium314.keyboard.latin.tableime.TableImeWordPredictor(words)

        // Predict after "香"
        val resHeong = predictor.predict("香")
        println("Predict 香: $resHeong")
        assertTrue(resHeong.contains("港"))
        assertTrue(resHeong.contains("水"))
        assertTrue(resHeong.contains("蕉"))
        // 1-char continuation "港" should come before 2-char continuation "港人"
        val idxKong = resHeong.indexOf("港")
        val idxKongYan = resHeong.indexOf("港人")
        assertTrue(idxKong < idxKongYan)

        // Predict after "香港"
        val resHeongKong = predictor.predict("香港")
        println("Predict 香港: $resHeongKong")
        assertTrue(resHeongKong.contains("人"))
        assertTrue(resHeongKong.contains("島"))

        // Predict after "我想食" (context suffix fallback to "食")
        val resSik = predictor.predict("我想食")
        println("Predict 我想食: $resSik")
        assertTrue(resSik.contains("飯"))
        assertTrue(resSik.contains("物"))
        assertTrue(resSik.contains("嘢"))
        val idxFaan = resSik.indexOf("飯")
        val idxFaanMei = resSik.indexOf("飯未")
        assertTrue(idxFaan < idxFaanMei)
    }

    @Test
    fun testWordPredictorRealAsset() {
        val file = java.io.File("src/main/assets/tableime/cangjie_words.tsv")
        assertTrue(file.exists())
        val words = file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) null
            else {
                val tabIdx = trimmed.indexOf('\t')
                if (tabIdx != -1) trimmed.substring(0, tabIdx).trim() else trimmed
            }
        }
        assertTrue(words.size > 100000)
        val predictor = helium314.keyboard.latin.tableime.TableImeWordPredictor(words)

        val resHeong = predictor.predict("香")
        println("Real asset predict 香: ${resHeong.take(10)}")
        assertTrue(resHeong.contains("港"))
        assertTrue(resHeong.contains("水"))
        assertTrue(resHeong.contains("蕉"))

        val resHeongKong = predictor.predict("香港")
        println("Real asset predict 香港: ${resHeongKong.take(10)}")
        assertTrue(resHeongKong.contains("人"))
        assertTrue(resHeongKong.contains("島"))

        val resPak = predictor.predict("北")
        println("Real asset predict 北: ${resPak.take(10)}")
        assertTrue(resPak.contains("京"))
        assertTrue(resPak.contains("方"))
        assertTrue(resPak.contains("極"))

        val resSik = predictor.predict("食")
        println("Real asset predict 食: ${resSik.take(10)}")
        assertTrue(resSik.contains("飯"))
        assertTrue(resSik.contains("物"))
        assertTrue(resSik.contains("嘢"))
    }

    @Test
    fun testTableImeSuggestedWordsPrediction() {
        val list = arrayListOf(
            helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo(
                "港", "", 1000, helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo.KIND_PREDICTION,
                helium314.keyboard.latin.dictionary.Dictionary.DICTIONARY_USER_TYPED, 0, -1
            )
        )
        val pred = helium314.keyboard.latin.tableime.TableImeSuggestedWords(list, 1, true)
        assertTrue(pred.isPrediction)
        assertEquals(helium314.keyboard.latin.InputStyle.PREDICTION, pred.mInputStyle)
        // Ensure auto-commit candidate is null so space does not commit predictions!
        kotlin.test.assertNull(pred.autoCommitCandidate)
        kotlin.test.assertNull(pred.autoCorrectionWord)
        kotlin.test.assertNull(pred.autoCorrectionWordInfo)

        assertEquals("tableime/cangjie_words.bin", CangjieSpec.nextWordsAsset)
    }

    @Test
    fun testFrequentWordsOptionAOrdering() {
        val baseEntries = listOf(
            TableImeTable.Entry("a", "日"),
            TableImeTable.Entry("a", "曰"),
            TableImeTable.Entry("aa", "昌"),
            TableImeTable.Entry("aaa", "晶"),
            TableImeTable.Entry("ab", "明")
        )
        val table = TableImeTable(baseEntries)

        // Without frequent words: default order
        val normal = table.lookup("a", frequentWords = null)
        assertEquals(listOf("日", "曰", "昌", "晶", "明"), normal)

        // With "曰" in frequent words:
        // "曰" should move ahead of "日" in Exact matches.
        // Exact matches ("曰", "日") MUST stay ahead of Prefix matches ("昌", "晶", "明")!
        val withYu = table.lookup("a", frequentWords = setOf("曰"))
        assertEquals(listOf("曰", "日", "昌", "晶", "明"), withYu)

        // With "曰" and "明" in frequent words:
        // "曰" is ahead of "日" (in exact matches)
        // "明" is ahead of "昌" and "晶" (in prefix matches)
        // Exact matches ("曰", "日") STILL stay ahead of Prefix match "明"!
        val withYuAndMing = table.lookup("a", frequentWords = setOf("曰", "明"))
        assertEquals(listOf("曰", "日", "明", "昌", "晶"), withYuAndMing)

        // Only "明" (a prefix match) in frequent words:
        // "明" floats ahead of "昌" and "晶", but NEVER jumps ahead of exact matches ("日", "曰")!
        val withMingOnly = table.lookup("a", frequentWords = setOf("明"))
        assertEquals(listOf("日", "曰", "明", "昌", "晶"), withMingOnly)
    }

    @Test
    fun testFrequentWordCountsOptionAOrdering() {
        val baseEntries = listOf(
            TableImeTable.Entry("a", "日"),
            TableImeTable.Entry("a", "曰"),
            TableImeTable.Entry("aa", "昌"),
            TableImeTable.Entry("aaa", "晶"),
            TableImeTable.Entry("ab", "明")
        )
        val table = TableImeTable(baseEntries)

        // Exact: 日 (count 10), 曰 (count 5) -> 日 > 曰
        // Prefix: 昌 (count 8), 明 (count 2), 晶 (0) -> 昌 > 明 > 晶
        // Option A: exact matches strictly precede prefix matches!
        val counts = mapOf(
            "曰" to 5,
            "日" to 10,
            "明" to 2,
            "昌" to 8
        )
        val result = table.lookup("a", frequentWordCounts = counts)
        assertEquals(listOf("日", "曰", "昌", "明", "晶"), result)

        // If 曰 has higher count than 日
        val counts2 = mapOf(
            "曰" to 20,
            "日" to 10,
            "明" to 30, // Even though 明 has 30, it is a prefix match so it must NEVER jump ahead of exact matches!
            "昌" to 5
        )
        val result2 = table.lookup("a", frequentWordCounts = counts2)
        assertEquals(listOf("曰", "日", "明", "昌", "晶"), result2)
    }

    @Test
    fun testWordPredictorFrequencyOrdering() {
        val words = listOf(
            "香港",
            "香水",
            "香腸",
            "香蕉",
            "香味"
        ).sorted()
        val predictor = helium314.keyboard.latin.tableime.TableImeWordPredictor(words)

        // Default: length/alphabetic
        val defaultList = predictor.predict("香")
        assertTrue(defaultList.contains("港"))
        assertTrue(defaultList.contains("蕉"))

        // With frequency counts: 蕉 (count 10) > 港 (count 3)
        val counts = mapOf("蕉" to 10, "港" to 3)
        val sortedList = predictor.predict("香", frequentWordCounts = counts)
        assertEquals("蕉", sortedList[0])
        assertEquals("港", sortedList[1])
        // Remaining unranked ones follow
        assertTrue(sortedList.contains("水"))
        assertTrue(sortedList.contains("腸"))
        assertTrue(sortedList.contains("味"))
    }

    private fun createBinaryTable(entries: Map<String, List<String>>): TableImeTable.BinarySource {
        val sortedCodes = entries.keys.sorted()
        val candData = java.io.ByteArrayOutputStream()
        val indexList = mutableListOf<Triple<ByteArray, Int, Int>>()

        for (code in sortedCodes) {
            val codeBytes = code.toByteArray(Charsets.US_ASCII)
            val codePadded = ByteArray(8)
            System.arraycopy(codeBytes, 0, codePadded, 0, codeBytes.size)

            val cands = entries[code] ?: emptyList()
            val offset = candData.size()
            for (c in cands) {
                val b = c.toByteArray(Charsets.UTF_8)
                candData.write(b.size)
                candData.write(b)
            }
            indexList.add(Triple(codePadded, offset, cands.size))
        }

        val totalSize = 16 + indexList.size * 16 + candData.size()
        val buf = java.nio.ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf('C'.code.toByte(), 'J'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte()))
        buf.putShort(1) // version
        buf.putShort(0) // flags
        buf.putInt(indexList.size)
        buf.putInt(16 + indexList.size * 16)

        for ((codePadded, offset, count) in indexList) {
            buf.put(codePadded)
            buf.putInt(offset)
            buf.putShort(count.toShort())
            buf.putShort(0) // padding
        }
        buf.put(candData.toByteArray())
        buf.position(0)
        return TableImeTable.BinarySource(buf)
    }

    private fun createBinaryWordPredictor(words: List<String>): TableImeWordPredictor.BinarySource {
        val sortedWords = words.sortedBy { it }
        val wordData = java.io.ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        for (w in sortedWords) {
            offsets.add(wordData.size())
            val b = w.toByteArray(Charsets.UTF_8)
            wordData.write(b.size)
            wordData.write(b)
        }

        val totalSize = 16 + offsets.size * 4 + wordData.size()
        val buf = java.nio.ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf('C'.code.toByte(), 'J'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte()))
        buf.putShort(1)
        buf.putShort(0)
        buf.putInt(offsets.size)
        buf.putInt(16 + offsets.size * 4)

        for (off in offsets) {
            buf.putInt(off)
        }
        buf.put(wordData.toByteArray())
        buf.position(0)
        return TableImeWordPredictor.BinarySource(buf)
    }

    @Test
    fun testBinaryTableSourceLookup() {
        val baseMap = mapOf(
            "a" to listOf("日", "曰"),
            "aa" to listOf("昌"),
            "aaa" to listOf("晶"),
            "ab" to listOf("明"),
            "b" to listOf("月"),
            "d" to listOf("木"),
            "dd" to listOf("林"),
            "ddd" to listOf("森")
        )
        val extMap = mapOf(
            "aa" to listOf("昍"),
            "ab" to listOf("冐")
        )

        val baseSource = createBinaryTable(baseMap)
        val extSource = createBinaryTable(extMap)
        val table = TableImeTable(baseSource, extSource)

        // Exact match 'a' without extended
        val resA = table.lookup("a", includeExtended = false)
        assertEquals("日", resA[0])
        assertEquals("曰", resA[1])
        assertTrue(resA.contains("昌"))
        assertTrue(resA.contains("晶"))
        assertTrue(resA.contains("明"))
        assertFalse(resA.contains("昍"))

        // Exact match 'a' with extended
        val resAExt = table.lookup("a", includeExtended = true)
        assertTrue(resAExt.contains("昍"))

        // Exact match 'ab'
        val resAB = table.lookup("ab", includeExtended = true)
        assertEquals("明", resAB[0])
        assertEquals("冐", resAB[1])

        // Option A ordering with counts
        val counts = mapOf("曰" to 20, "日" to 10, "明" to 30, "昌" to 5)
        val resCounts = table.lookup("a", frequentWordCounts = counts)
        assertEquals(listOf("曰", "日", "明", "昌", "晶"), resCounts)
    }

    @Test
    fun testBinaryWordPredictorSource() {
        val words = listOf("香港", "香水", "香腸", "香蕉", "香味")
        val predictor = TableImeWordPredictor(createBinaryWordPredictor(words))

        val defaultList = predictor.predict("香")
        assertTrue(defaultList.contains("港"))
        assertTrue(defaultList.contains("蕉"))

        val counts = mapOf("蕉" to 10, "港" to 3)
        val sortedList = predictor.predict("香", frequentWordCounts = counts)
        assertEquals("蕉", sortedList[0])
        assertEquals("港", sortedList[1])
        assertTrue(sortedList.contains("水"))
        assertTrue(sortedList.contains("腸"))
        assertTrue(sortedList.contains("味"))
    }

    @Test
    fun testTableImeUserHistoryFrequentWords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tableId = "test_frequent_words"
        TableImeUserHistory.clear(context, tableId)

        // Record words
        TableImeUserHistory.recordWord(context, tableId, "好")
        TableImeUserHistory.recordWord(context, tableId, "好")
        TableImeUserHistory.recordWord(context, tableId, "日")

        val counts = TableImeUserHistory.getFrequentWordCounts(context, tableId)
        assertEquals(2, counts["好"])
        assertEquals(1, counts["日"])

        // Clear frequent words
        TableImeUserHistory.clearFrequentWords(context, tableId)
        val clearedCounts = TableImeUserHistory.getFrequentWordCounts(context, tableId)
        assertTrue(clearedCounts.isEmpty())
    }

    @Test
    fun testTableImeUserHistoryBigramLearning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tableId = "test_bigrams"
        TableImeUserHistory.clear(context, tableId)

        // Record bigrams
        TableImeUserHistory.recordBigram(context, tableId, "香港", "人")
        TableImeUserHistory.recordBigram(context, tableId, "香港", "人")
        TableImeUserHistory.recordBigram(context, tableId, "香港", "茶餐廳")
        TableImeUserHistory.recordBigram(context, tableId, "去", "玩")

        // Exact match on prevWord
        val predsExact = TableImeUserHistory.getLearnedPredictions(context, tableId, "香港")
        assertEquals(listOf("人", "茶餐廳"), predsExact)

        // Suffix match on longer context
        val predsSuffix = TableImeUserHistory.getLearnedPredictions(context, tableId, "我愛香港")
        assertEquals(listOf("人", "茶餐廳"), predsSuffix)

        val predsSingle = TableImeUserHistory.getLearnedPredictions(context, tableId, "去")
        assertEquals(listOf("玩"), predsSingle)

        // Clear learned predictions
        TableImeUserHistory.clearLearnedPredictions(context, tableId)
        val clearedPreds = TableImeUserHistory.getLearnedPredictions(context, tableId, "香港")
        assertTrue(clearedPreds.isEmpty())
    }

    @Test
    fun testCandidatePriorityBasePrefixBeforeExtExact() {
        // Base table: 'vnd' -> "好", 'vnf' -> "妳"
        // Extended table: 'vn' -> "𠃑"
        val baseMap = mapOf(
            "vnd" to listOf("好"),
            "vnf" to listOf("妳")
        )
        val extMap = mapOf(
            "vn" to listOf("𠃑")
        )
        val baseSource = createBinaryTable(baseMap)
        val extSource = createBinaryTable(extMap)
        val table = TableImeTable(baseSource, extSource)

        val cands = table.lookup("vn", includeExtended = true)
        // Order: Base prefix ("好", "妳") MUST precede Extended exact ("𠃑")
        assertEquals(listOf("好", "妳", "𠃑"), cands)
    }
}

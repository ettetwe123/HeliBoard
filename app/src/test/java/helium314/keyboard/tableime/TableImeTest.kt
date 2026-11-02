// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.tableime

import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.tableime.CangjieSpec
import helium314.keyboard.latin.tableime.TableImeCombiner
import helium314.keyboard.latin.tableime.TableImeRegistry
import helium314.keyboard.latin.tableime.TableImeTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val file = java.io.File("src/main/assets/tableime/cangjie5_base.tsv")
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
        val file = java.io.File("src/main/assets/tableime/jyut6ping3_words.tsv")
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

        assertEquals("tableime/jyut6ping3_words.tsv", CangjieSpec.nextWordsAsset)
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
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

object CangjieSpec : TableImeSpec {
    override val id: String = "cangjie"
    override val combiningRules: String = "cangjie"
    override val maxCodeLength: Int = 5
    override val baseTableAsset: String = "tableime/cangjie5_base.bin"
    override val extendedTableAsset: String = "tableime/cangjie5_extended.bin"
    override val nextWordsAsset: String = "tableime/cangjie5_words.bin"

    private val RADICALS = mapOf(
        'a' to "日",
        'b' to "月",
        'c' to "金",
        'd' to "木",
        'e' to "水",
        'f' to "火",
        'g' to "土",
        'h' to "竹",
        'i' to "戈",
        'j' to "十",
        'k' to "大",
        'l' to "中",
        'm' to "一",
        'n' to "弓",
        'o' to "人",
        'p' to "心",
        'q' to "手",
        'r' to "口",
        's' to "尸",
        't' to "廿",
        'u' to "山",
        'v' to "女",
        'w' to "田",
        'x' to "難",
        'y' to "卜",
        'z' to "重"
    )

    override fun codeToRadical(c: Char): String =
        RADICALS[c.lowercaseChar()] ?: c.toString()
}

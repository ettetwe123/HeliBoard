// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

object CangjieSpec : TableImeSpec {
    override val id: String = "cangjie"
    override val combiningRules: String = "cangjie"
    override val maxCodeLength: Int = 5
    override val baseTableAsset: String = "tableime/cangjie_base.bin"
    override val extendedTableAsset: String = "tableime/cangjie_extended.bin"
    override val nextWordsAsset: String = "tableime/cangjie_words.bin"

    val CANGJIE_QWERTY_NEIGHBORS: Map<Char, List<Char>> = mapOf(
        'q' to listOf('w', 'a', 's'),
        'w' to listOf('q', 'e', 's', 'a', 'd'),
        'e' to listOf('w', 'r', 'd', 's', 'f'),
        'r' to listOf('e', 't', 'f', 'd', 'g'),
        't' to listOf('r', 'y', 'g', 'f', 'h'),
        'y' to listOf('t', 'u', 'h', 'g', 'j'),
        'u' to listOf('y', 'i', 'j', 'h', 'k'),
        'i' to listOf('u', 'o', 'k', 'j', 'l'),
        'o' to listOf('i', 'p', 'l', 'k'),
        'p' to listOf('o', 'l'),

        'a' to listOf('q', 'w', 's', 'z'),
        's' to listOf('a', 'd', 'w', 'e', 'z', 'x'),
        'd' to listOf('s', 'f', 'e', 'r', 'x', 'c'),
        'f' to listOf('d', 'g', 'r', 't', 'c', 'v'),
        'g' to listOf('f', 'h', 't', 'y', 'v', 'b'),
        'h' to listOf('g', 'j', 'y', 'u', 'b', 'n'),
        'j' to listOf('h', 'k', 'u', 'i', 'n', 'm'),
        'k' to listOf('j', 'l', 'i', 'o', 'm'),
        'l' to listOf('k', 'o', 'p'),

        'z' to listOf('a', 's', 'x'),
        'x' to listOf('z', 'c', 's', 'd'),
        'c' to listOf('x', 'v', 'd', 'f'),
        'v' to listOf('c', 'b', 'f', 'g'),
        'b' to listOf('v', 'n', 'g', 'h'),
        'n' to listOf('b', 'm', 'h', 'j'),
        'm' to listOf('n', 'j', 'k')
    )

    override val fuzzyPolicy = object : TableImeFuzzyPolicy {
        override val enabled: Boolean = true
        override val minCodeLength: Int = 3
        override val allowExtraKeyDeletion: Boolean = true
        override val directMatchThreshold: Int = 10
        override val neighborKeys: Map<Char, List<Char>> = CANGJIE_QWERTY_NEIGHBORS
    }

    override val rankingPolicy = object : TableImeRankingPolicy {
        override val preserveHomocodeTableOrder: Boolean = true
        override val allowPrefixMatching: Boolean = true
    }

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

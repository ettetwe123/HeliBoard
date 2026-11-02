// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

interface TableImeSpec {
    val id: String
    val combiningRules: String
    val maxCodeLength: Int
    val baseTableAsset: String
    val extendedTableAsset: String?

    fun codeToRadical(c: Char): String
    fun getRadicalString(code: String): String {
        val sb = StringBuilder(code.length)
        for (c in code) {
            sb.append(codeToRadical(c))
        }
        return sb.toString()
    }
}

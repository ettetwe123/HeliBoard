// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

/**
 * Policy controlling typo tolerance (fuzzy matching) for a Table IME.
 * Decouples engine implementation from specific IME conventions (e.g. Cangjie vs Quick vs Wubi).
 */
interface TableImeFuzzyPolicy {
    /** Whether typo tolerance (fuzzy matching) is supported for this IME. */
    val enabled: Boolean get() = false

    /** Minimum code length required before attempting fuzzy match. */
    val minCodeLength: Int get() = 3

    /** Whether extra-key deletion (e.g. dropping a character on dead-end) is permitted. */
    val allowExtraKeyDeletion: Boolean get() = false

    /** Candidate count threshold: fuzzy matching only runs if direct matches are fewer than this. */
    val directMatchThreshold: Int get() = 10

    /** Key adjacency map for spatial neighbor typo tolerance (e.g. QWERTY neighbors). */
    val neighborKeys: Map<Char, List<Char>> get() = emptyMap()

    companion object {
        val DEFAULT = object : TableImeFuzzyPolicy {}
    }
}

/**
 * Policy controlling candidate ranking, prefix matching, and homocode stability for a Table IME.
 */
interface TableImeRankingPolicy {
    /**
     * Whether exact match candidates that have been used (count > 0) retain their
     * original base table order to prevent homocode leapfrog oscillation (e.g. 有 vs 冇).
     */
    val preserveHomocodeTableOrder: Boolean get() = true

    /** Whether prefix matches (continuations of the currently typed code) are allowed. */
    val allowPrefixMatching: Boolean get() = true

    companion object {
        val DEFAULT = object : TableImeRankingPolicy {}
    }
}

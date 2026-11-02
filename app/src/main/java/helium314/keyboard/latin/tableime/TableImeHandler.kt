// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import helium314.keyboard.latin.LastComposedWord
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Settings

object TableImeHandler {
    class CommitResult(val text: String, val commitType: Int)

    @JvmStatic
    fun isTableIme(combiningSpec: String?): Boolean {
        return TableImeRegistry.isTableIme(combiningSpec)
    }

    @JvmStatic
    fun shouldAutoSpace(combiningSpec: String?): Boolean {
        return !isTableIme(combiningSpec)
    }

    @JvmStatic
    fun shouldUpdateSuggestions(suggestedWords: SuggestedWords, combiningSpec: String?): Boolean {
        return suggestedWords.mIsTableIme || isTableIme(combiningSpec)
    }

    @JvmStatic
    fun handleSpace(
        inputLogic: InputLogic,
        wordComposer: WordComposer,
        suggestedWords: SuggestedWords?,
        handler: LatinIME.UIHandler
    ): Boolean {
        if (!wordComposer.isComposingWord || !isTableIme(wordComposer.combiningSpec)) {
            return false
        }
        val result = resolveCommit(suggestedWords, wordComposer, isEnter = false)
        if (result != null) {
            onWordCommitted(wordComposer.combiningSpec, result.text)
            inputLogic.commitTableImeWord(result.text, result.commitType, handler)
        }
        return true
    }

    @JvmStatic
    fun handleEnter(
        inputLogic: InputLogic,
        wordComposer: WordComposer,
        suggestedWords: SuggestedWords?,
        handler: LatinIME.UIHandler
    ): Boolean {
        if (!wordComposer.isComposingWord || !isTableIme(wordComposer.combiningSpec)) {
            return false
        }
        val result = resolveCommit(suggestedWords, wordComposer, isEnter = true)
        if (result != null) {
            onWordCommitted(wordComposer.combiningSpec, result.text)
            inputLogic.commitTableImeWord(result.text, result.commitType, handler)
        }
        return true
    }

    @JvmStatic
    fun onWordCommitted(combiningSpec: String?, word: String) {
        if (!isTableIme(combiningSpec) || word.isEmpty()) return
        val currentSubtype = RichInputMethodManager.getInstance().currentSubtype
        if (!currentSubtype.hasExtraValue(Constants.Subtype.ExtraValue.TABLE_IME_FREQ_WORDS)) return
        val context = Settings.getCurrentContext() ?: return
        TableImeUserHistory.recordWord(context, combiningSpec!!, word)
    }

    /**
     * Resolves the candidate word to commit when space or enter is pressed during composition.
     * Returns null if space was pressed but no candidate was found (so nothing should be committed).
     */
    @JvmStatic
    fun resolveCommit(
        suggestedWords: SuggestedWords?,
        wordComposer: WordComposer,
        isEnter: Boolean
    ): CommitResult? {
        // 1. Try to get candidate 0 from suggestedWords
        var firstWord: String? = null
        if (suggestedWords != null && suggestedWords.size() > 0) {
            firstWord = suggestedWords.autoCorrectionWord ?: suggestedWords.getInfo(0).mWord
        }

        // 2. If suggestedWords didn't have it, query table directly
        if (firstWord == null && wordComposer.isComposingWord) {
            val rawCode = wordComposer.rawComposingCode
            val combiningSpec = wordComposer.combiningSpec
            if (!rawCode.isNullOrEmpty() && combiningSpec != null) {
                val context = Settings.getCurrentContext()
                if (context != null) {
                    val table = TableImeRegistry.getTable(context, combiningSpec)
                    if (table != null) {
                        val currentSubtype = RichInputMethodManager.getInstance().currentSubtype
                        val includeExtended = currentSubtype.hasExtraValue(Constants.Subtype.ExtraValue.TABLE_IME_EXTENDED)
                        val adjustFreq = currentSubtype.hasExtraValue(Constants.Subtype.ExtraValue.TABLE_IME_FREQ_WORDS)
                        val frequentWords = if (adjustFreq) TableImeUserHistory.getFrequentWordCounts(context, combiningSpec) else null
                        val candidates = table.lookup(rawCode, includeExtended = includeExtended, frequentWordCounts = frequentWords)
                        if (candidates.isNotEmpty()) {
                            firstWord = candidates[0]
                        }
                    }
                }
            }
        }

        return if (firstWord != null) {
            CommitResult(firstWord, LastComposedWord.COMMIT_TYPE_MANUAL_PICK)
        } else if (isEnter) {
            CommitResult(wordComposer.typedWord, LastComposedWord.COMMIT_TYPE_USER_TYPED_WORD)
        } else {
            null
        }
    }
}

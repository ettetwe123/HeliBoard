// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import helium314.keyboard.latin.LastComposedWord
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Settings

object TableImeHandler {

    @Volatile
    private var lastCommittedWord: String? = null

    @JvmStatic
    fun getLastCommittedWord(): String? = lastCommittedWord

    @JvmStatic
    fun resetLastCommittedWord() {
        lastCommittedWord = null
    }
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
        val context = Settings.getCurrentContext() ?: return

        // 1. Record single character frequency
        if (currentSubtype.hasExtraValue(Constants.Subtype.ExtraValue.TABLE_IME_FREQ_WORDS)) {
            TableImeUserHistory.recordWord(context, combiningSpec!!, word)
        }

        // 2. Record dynamic bigram association
        val learnEnabled = currentSubtype.getExtraValueOf(Constants.Subtype.ExtraValue.TABLE_IME_LEARN_PREDICTIONS)?.toBoolean() ?: true
        val prev = lastCommittedWord
        if (learnEnabled && !prev.isNullOrEmpty()) {
            TableImeUserHistory.recordBigram(context, combiningSpec!!, prev, word)
        }
        lastCommittedWord = word
    }

    @JvmStatic
    fun onFinishInput(context: Context) {
        lastCommittedWord = null
        TableImeUserHistory.flush(context)
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
        var firstWord: String? = null

        // 1. Always query table synchronously using wordComposer's current rawComposingCode!
        // When typing fast, suggestedWords is computed asynchronously on a background thread and may be
        // stale or default LatinIME SuggestedWords (where getInfo(0) is typedWord/字碼).
        // Synchronous table lookup (< 20μs) eliminates race conditions and guarantees we commit the character, not the code!
        if (wordComposer.isComposingWord) {
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
                        val fuzzyEnabled = currentSubtype.getExtraValueOf(Constants.Subtype.ExtraValue.TABLE_IME_FUZZY)?.toBoolean() ?: true
                        val predictor = TableImeRegistry.getWordPredictor(context, combiningSpec)
                        val isCommon: (String) -> Boolean = { word ->
                            (frequentWords?.containsKey(word) == true) || (predictor?.isCommon(word) == true)
                        }
                        val candidates = table.lookup(
                            rawCode,
                            includeExtended = includeExtended,
                            frequentWordCounts = frequentWords,
                            enableFuzzy = fuzzyEnabled,
                            isCommonWord = isCommon
                        )
                        if (candidates.isNotEmpty()) {
                            firstWord = candidates[0]
                        }
                    }
                }
            }
        }

        // 2. Fallback to suggestedWords ONLY if it's a valid TableImeSuggestedWords (and not prediction)
        if (firstWord == null && suggestedWords != null && suggestedWords.mIsTableIme && suggestedWords.size() > 0) {
            val isPred = (suggestedWords as? TableImeSuggestedWords)?.isPrediction == true
            if (!isPred) {
                firstWord = suggestedWords.autoCorrectionWord ?: suggestedWords.getInfo(0).mWord
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

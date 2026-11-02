// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.latin.InputStyle;
import helium314.keyboard.latin.SuggestedWords;

import java.util.ArrayList;

public class TableImeSuggestedWords extends SuggestedWords {

    public TableImeSuggestedWords(
            @NonNull final ArrayList<SuggestedWordInfo> candidates,
            final int sequenceNumber
    ) {
        super(
                candidates,
                null /* rawSuggestions */,
                candidates.isEmpty() ? null : candidates.get(0) /* typedWordInfo */,
                !candidates.isEmpty() /* typedWordValid */,
                false /* willAutoCorrect: disable auto-correction underline (blue highlight) */,
                false /* isObsoleteSuggestions */,
                InputStyle.NONE,
                sequenceNumber,
                true /* isTableIme */
        );
    }

    public static TableImeSuggestedWords empty(final int sequenceNumber) {
        return new TableImeSuggestedWords(new ArrayList<>(0), sequenceNumber);
    }

    @Override
    public int getWordCountToShow() {
        return size();
    }

    @Nullable
    @Override
    public SuggestedWordInfo getAutoCorrectionWordInfo() {
        return mSuggestedWordInfoList.isEmpty() ? null : mSuggestedWordInfoList.get(0);
    }

    @Nullable
    @Override
    public String getAutoCorrectionWord() {
        return mSuggestedWordInfoList.isEmpty() ? null : mSuggestedWordInfoList.get(0).mWord;
    }

    @Nullable
    @Override
    public SuggestedWordInfo getAutoCommitCandidate() {
        return mSuggestedWordInfoList.isEmpty() ? null : mSuggestedWordInfoList.get(0);
    }
}

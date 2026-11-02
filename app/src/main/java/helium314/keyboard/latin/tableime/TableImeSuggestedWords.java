// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.latin.InputStyle;
import helium314.keyboard.latin.SuggestedWords;

import java.util.ArrayList;

public class TableImeSuggestedWords extends SuggestedWords {
    private final boolean mIsPrediction;

    public TableImeSuggestedWords(
            @NonNull final ArrayList<SuggestedWordInfo> candidates,
            final int sequenceNumber
    ) {
        this(candidates, sequenceNumber, false);
    }

    public TableImeSuggestedWords(
            @NonNull final ArrayList<SuggestedWordInfo> candidates,
            final int sequenceNumber,
            final boolean isPrediction
    ) {
        super(
                candidates,
                null /* rawSuggestions */,
                candidates.isEmpty() ? null : candidates.get(0) /* typedWordInfo */,
                !candidates.isEmpty() /* typedWordValid */,
                false /* willAutoCorrect: disable auto-correction underline (blue highlight) */,
                false /* isObsoleteSuggestions */,
                isPrediction ? InputStyle.PREDICTION : InputStyle.NONE,
                sequenceNumber,
                true /* isTableIme */
        );
        mIsPrediction = isPrediction;
    }

    public static TableImeSuggestedWords empty(final int sequenceNumber) {
        return new TableImeSuggestedWords(new ArrayList<>(0), sequenceNumber, false);
    }

    public boolean isPrediction() {
        return mIsPrediction;
    }

    @Override
    public int getWordCountToShow() {
        return size();
    }

    @Nullable
    @Override
    public SuggestedWordInfo getAutoCorrectionWordInfo() {
        if (mIsPrediction) return null;
        return mSuggestedWordInfoList.isEmpty() ? null : mSuggestedWordInfoList.get(0);
    }

    @Nullable
    @Override
    public String getAutoCorrectionWord() {
        if (mIsPrediction) return null;
        return mSuggestedWordInfoList.isEmpty() ? null : mSuggestedWordInfoList.get(0).mWord;
    }

    @Nullable
    @Override
    public SuggestedWordInfo getAutoCommitCandidate() {
        if (mIsPrediction) return null;
        return mSuggestedWordInfoList.isEmpty() ? null : mSuggestedWordInfoList.get(0);
    }
}

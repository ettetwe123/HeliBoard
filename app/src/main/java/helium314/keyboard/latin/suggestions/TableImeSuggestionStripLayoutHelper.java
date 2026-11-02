// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import helium314.keyboard.latin.SuggestedWords;

import java.util.ArrayList;

public class TableImeSuggestionStripLayoutHelper extends SuggestionStripLayoutHelper {
    public static final int CJK_SUGGESTIONS_COUNT_IN_STRIP = 5;

    public TableImeSuggestionStripLayoutHelper(
            final Context context,
            final AttributeSet attrs,
            final int defStyle,
            final ArrayList<TextView> wordViews,
            final ArrayList<View> dividerViews,
            final ArrayList<TextView> debugInfoViews
    ) {
        super(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews);
        mSuggestionsCountInStrip = CJK_SUGGESTIONS_COUNT_IN_STRIP;
        mCenterPositionInStrip = mSuggestionsCountInStrip / 2; // = 2
        mTypedWordPositionWhenAutocorrect = mCenterPositionInStrip - 1; // = 1
        mCenterSuggestionWeight = 1.0f / mSuggestionsCountInStrip;
    }

    @Override
    protected float getSuggestionWeight(final int positionInStrip) {
        // Equal width for Chinese square characters
        return 1.0f / mSuggestionsCountInStrip;
    }

    @Override
    protected int getPositionInSuggestionStrip(
            final int indexInSuggestedWords,
            final SuggestedWords suggestedWords
    ) {
        // Index 0 (Best exact match, e.g. "好") goes to the center slot for space-commit
        if (indexInSuggestedWords == 0) {
            return mCenterPositionInStrip; // 2
        } else if (indexInSuggestedWords == 1) {
            return mCenterPositionInStrip - 1; // 1 (left of center)
        } else if (indexInSuggestedWords == 2) {
            return mCenterPositionInStrip + 1; // 3 (right of center)
        } else if (indexInSuggestedWords == 3) {
            return mCenterPositionInStrip - 2; // 0 (far left)
        } else if (indexInSuggestedWords == 4) {
            return mCenterPositionInStrip + 2; // 4 (far right)
        }
        return -1; // Overflow candidates go into MoreSuggestions panel
    }

    @Override
    protected CharSequence getStyledSuggestedWord(
            final SuggestedWords suggestedWords,
            final int indexInSuggestedWords
    ) {
        if (indexInSuggestedWords >= suggestedWords.size()) {
            return null;
        }
        final String word = suggestedWords.getLabel(indexInSuggestedWords);
        // Only the center suggestion (index 0) MUST be bold!
        if (indexInSuggestedWords == 0) {
            final Spannable spannedWord = new SpannableString(word);
            addStyleSpan(spannedWord, BOLD_SPAN);
            return spannedWord;
        }
        return word;
    }

    @Override
    protected int getSuggestionTextColor(
            final SuggestedWords suggestedWords,
            final int indexInSuggestedWords
    ) {
        if (indexInSuggestedWords == 0) {
            return mColorAutoCorrect;
        }
        return mColorSuggested;
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.latin.SuggestedWords;

import java.util.ArrayList;

public class TableImeSuggestionStripLayoutHelper extends SuggestionStripLayoutHelper {

    public TableImeSuggestionStripLayoutHelper(
            final Context context,
            final AttributeSet attrs,
            final int defStyle,
            final ArrayList<TextView> wordViews,
            final ArrayList<View> dividerViews,
            final ArrayList<TextView> debugInfoViews
    ) {
        super(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews);
    }

    @Override
    public int layoutAndReturnStartIndexOfMoreSuggestions(
            final Context context,
            final SuggestedWords suggestedWords,
            final ViewGroup stripView,
            final ViewGroup placerView
    ) {
        final int totalAvailable = Math.min(suggestedWords.size(), mWordViews.size());

        // Clear previous state of all word views
        for (int i = 0; i < mWordViews.size(); i++) {
            final TextView wordView = mWordViews.get(i);
            wordView.setText(null);
            wordView.setTag(null);
        }

        final int horizontalPadding = (int) (14 * context.getResources().getDisplayMetrics().density);

        for (int index = 0; index < totalAvailable; index++) {
            if (index != 0 && index < mDividerViews.size()) {
                final View divider = mDividerViews.get(index);
                stripView.addView(divider);
                final LinearLayout.LayoutParams dividerParams = (LinearLayout.LayoutParams) divider.getLayoutParams();
                if (dividerParams != null) {
                    dividerParams.gravity = Gravity.CENTER;
                }
            }

            final TextView wordView = mWordViews.get(index);
            final CharSequence styledWord = getStyledSuggestedWord(suggestedWords, index);
            wordView.setText(styledWord);
            wordView.setTag(index);
            wordView.setTextColor(getSuggestionTextColor(suggestedWords, index));
            wordView.setPadding(horizontalPadding, 0, horizontalPadding, 0);
            wordView.setEnabled(true);
            wordView.setVisibility(View.VISIBLE);
            wordView.setTextScaleX(1.0f);
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            wordView.setGravity(Gravity.CENTER);
            KeyboardTypeface.applyToTextView(wordView);

            stripView.addView(wordView);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            wordView.setLayoutParams(lp);
        }

        mMoreSuggestionsAvailable = (suggestedWords.size() > 0);
        return 0;
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
        // Candidate 0 (left-most) is the primary match committed by space -> BOLD!
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

    @Override
    protected int getPositionInSuggestionStrip(
            final int indexInSuggestedWords,
            final SuggestedWords suggestedWords
    ) {
        return indexInSuggestedWords;
    }
}

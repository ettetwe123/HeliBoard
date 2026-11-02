// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

class TableImeCandidatesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private lateinit var recyclerView: RecyclerView
    private var adapter: CandidatesAdapter? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        recyclerView = findViewById(R.id.table_ime_candidates_list)
        recyclerView.layoutManager = GridLayoutManager(context, 5)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val switcher = helium314.keyboard.keyboard.KeyboardSwitcher.getInstance()
        val keyboardView = switcher.mainKeyboardView
        val keyboard = switcher.keyboard
        val height = if (keyboardView != null && keyboardView.measuredHeight > 0) {
            keyboardView.measuredHeight
        } else if (keyboard != null && keyboard.mOccupiedHeight > 0) {
            keyboard.mOccupiedHeight + (keyboardView?.paddingTop ?: 0) + (keyboardView?.paddingBottom ?: 0)
        } else {
            ResourceUtils.getSecondaryKeyboardHeight(resources, Settings.getValues())
        }
        val width = if (keyboardView != null && keyboardView.measuredWidth > 0) {
            keyboardView.measuredWidth
        } else if (keyboard != null && keyboard.mOccupiedWidth > 0) {
            keyboard.mOccupiedWidth + (keyboardView?.paddingLeft ?: 0) + (keyboardView?.paddingRight ?: 0)
        } else {
            ResourceUtils.getKeyboardWidth(context, Settings.getValues())
        }
        val exactWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(exactWidthSpec, exactHeightSpec)
        setMeasuredDimension(width, height)
    }

    fun setCandidates(
        suggestedWords: SuggestedWords,
        onSelect: (SuggestedWords.SuggestedWordInfo) -> Unit
    ) {
        val colors = Settings.getValues().mColors
        setBackgroundColor(colors.get(ColorType.KEY_BACKGROUND))
        adapter = CandidatesAdapter(suggestedWords, onSelect)
        recyclerView.adapter = adapter
        recyclerView.scrollToPosition(0)
    }

    private class CandidatesAdapter(
        private val suggestedWords: SuggestedWords,
        private val onSelect: (SuggestedWords.SuggestedWordInfo) -> Unit
    ) : RecyclerView.Adapter<CandidateViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.table_ime_candidate_item,
                parent,
                false
            )
            return CandidateViewHolder(view)
        }

        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            val info = suggestedWords.getInfo(position)
            holder.bind(info, position, onSelect)
        }

        override fun getItemCount(): Int = suggestedWords.size()
    }

    private class CandidateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.candidate_text)

        fun bind(
            info: SuggestedWords.SuggestedWordInfo,
            position: Int,
            onSelect: (SuggestedWords.SuggestedWordInfo) -> Unit
        ) {
            val colors = Settings.getValues().mColors
            textView.text = info.mWord
            textView.setTextColor(colors.get(ColorType.KEY_TEXT))
            if (position == 0) {
                textView.typeface = Typeface.DEFAULT_BOLD
            } else {
                textView.typeface = Typeface.DEFAULT
            }
            itemView.setOnClickListener {
                onSelect(info)
            }
        }
    }
}

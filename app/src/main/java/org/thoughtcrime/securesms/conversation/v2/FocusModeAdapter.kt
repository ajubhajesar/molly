package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.thoughtcrime.securesms.conversation.ConversationMessage

/**
 * AJ fork: Focus Mode adapter.
 *
 * Center mode (default):
 *   outgoing: text slightly LEFT of center  →  "text <"  paddingEnd=96
 *   incoming: text slightly RIGHT of center →  "> text"  paddingStart=96
 *
 * LR mode (toggle):
 *   outgoing: right-aligned   incoming: left-aligned
 *
 * Opacity is scroll-driven via lastVisiblePosition.
 */
class FocusModeAdapter : ListAdapter<FocusModeAdapter.FocusItem, FocusModeAdapter.ViewHolder>(DIFF) {

  data class FocusItem(val text: String, val isOutgoing: Boolean)

  var lastVisiblePosition: Int = 0
  var isLeftRightMode: Boolean = false

  class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val tv: TextView = view as TextView
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val tv = TextView(parent.context).apply {
      layoutParams = RecyclerView.LayoutParams(
        RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.WRAP_CONTENT
      )
      textSize = 16f
      setTextColor(0xFFEEEEEE.toInt())
      typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
      letterSpacing = 0.02f
      setLineSpacing(0f, 1.4f)
    }
    return ViewHolder(tv)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = getItem(position)

    if (isLeftRightMode) {
      // Full left/right
      if (item.isOutgoing) {
        holder.tv.text = "${item.text}  \u003C"
        holder.tv.setPadding(40, 12, 40, 12)
        holder.tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
      } else {
        holder.tv.text = "\u003E  ${item.text}"
        holder.tv.setPadding(40, 12, 40, 12)
        holder.tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
      }
    } else {
      // Center mode: outgoing nudged LEFT, incoming nudged RIGHT
      if (item.isOutgoing) {
        holder.tv.text = "${item.text}  \u003C"
        // more padding on right pushes text left of center
        holder.tv.setPadding(48, 12, 112, 12)
        holder.tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
      } else {
        holder.tv.text = "\u003E  ${item.text}"
        // more padding on left pushes text right of center
        holder.tv.setPadding(112, 12, 48, 12)
        holder.tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
      }
    }

    // Scroll-driven opacity
    val distFromBottom = lastVisiblePosition - position
    holder.tv.alpha = when {
      distFromBottom <= 1  -> 1.0f
      distFromBottom <= 4  -> 0.80f
      distFromBottom <= 8  -> 0.50f
      distFromBottom <= 14 -> 0.25f
      else                 -> 0.08f
    }
  }

  companion object {
    fun fromConversationMessages(messages: List<ConversationMessage>): List<FocusItem> =
      messages
        .filter { it.messageRecord.body.isNotBlank() && !it.messageRecord.isUpdate }
        .map { FocusItem(it.messageRecord.body.trim(), it.messageRecord.isOutgoing) }
        .reversed()

    private val DIFF = object : DiffUtil.ItemCallback<FocusItem>() {
      override fun areItemsTheSame(a: FocusItem, b: FocusItem) = a == b
      override fun areContentsTheSame(a: FocusItem, b: FocusItem) = a == b
    }
  }
}

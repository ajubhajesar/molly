package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.thoughtcrime.securesms.conversation.ConversationMessage

/**
 * AJ fork: Focus Mode adapter.
 * Opacity is scroll-driven: fully visible at bottom of viewport, fades as distance from bottom increases.
 * Supports center mode and left/right mode toggle.
 */
class FocusModeAdapter : ListAdapter<FocusModeAdapter.FocusItem, FocusModeAdapter.ViewHolder>(DIFF) {

  data class FocusItem(val text: String, val isOutgoing: Boolean)

  /** Last visible item position — updated by scroll listener */
  var lastVisiblePosition: Int = Int.MAX_VALUE

  /** Toggle: false = center, true = left/right offset */
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
      setLineSpacing(0f, 1.3f)
    }
    return ViewHolder(tv)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = getItem(position)

    // Text + alignment
    if (isLeftRightMode) {
      if (item.isOutgoing) {
        holder.tv.text = "${item.text}  \u003C"
        holder.tv.setPadding(96, 11, 40, 11)
        holder.tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
      } else {
        holder.tv.text = "\u003E  ${item.text}"
        holder.tv.setPadding(40, 11, 96, 11)
        holder.tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
      }
    } else {
      // Center mode
      holder.tv.text = if (item.isOutgoing) "${item.text}  \u003C" else "\u003E  ${item.text}"
      holder.tv.setPadding(56, 11, 56, 11)
      holder.tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    // Scroll-driven opacity: distance from bottom of visible area
    val distFromBottom = lastVisiblePosition - position
    holder.tv.alpha = when {
      distFromBottom <= 0  -> 1.0f   // at or below last visible = full
      distFromBottom <= 3  -> 0.85f
      distFromBottom <= 7  -> 0.55f
      distFromBottom <= 13 -> 0.28f
      else                 -> 0.10f
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

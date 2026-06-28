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
 * AJ fork: Focus Mode minimal adapter.
 * Outgoing: «text»   Incoming: text <
 * Oldest first (top), newest last (bottom).
 */
class FocusModeAdapter : ListAdapter<FocusModeAdapter.FocusItem, FocusModeAdapter.ViewHolder>(DIFF) {

  data class FocusItem(val text: String, val isOutgoing: Boolean)

  class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val tv: TextView = view as TextView
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val tv = TextView(parent.context).apply {
      layoutParams = RecyclerView.LayoutParams(
        RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.WRAP_CONTENT
      )
      textSize = 17f
      setTextColor(0xFFEEEEEE.toInt())
      textAlignment = View.TEXT_ALIGNMENT_CENTER
      setPadding(64, 14, 64, 14)
      typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
      letterSpacing = 0.03f
      setLineSpacing(0f, 1.3f)
    }
    return ViewHolder(tv)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = getItem(position)
    // «outgoing» or  incoming <
    holder.tv.text = if (item.isOutgoing) "\u00AB${item.text}\u00BB" else "${item.text} \u003C"
    // Opacity: newest (bottom) = full, fades upward
    val fromBottom = itemCount - 1 - position
    holder.tv.alpha = when {
      fromBottom <= 2  -> 1.0f
      fromBottom <= 6  -> 0.75f
      fromBottom <= 12 -> 0.45f
      else             -> 0.2f
    }
  }

  companion object {
    fun fromConversationMessages(messages: List<ConversationMessage>): List<FocusItem> =
      messages
        .filter { it.messageRecord.body.isNotBlank() && !it.messageRecord.isUpdate }
        .map { FocusItem(it.messageRecord.body.trim(), it.messageRecord.isOutgoing) }
        .reversed()  // Signal gives newest-first; we want oldest-first (top→bottom)

    private val DIFF = object : DiffUtil.ItemCallback<FocusItem>() {
      override fun areItemsTheSame(a: FocusItem, b: FocusItem) = a == b
      override fun areContentsTheSame(a: FocusItem, b: FocusItem) = a == b
    }
  }
}

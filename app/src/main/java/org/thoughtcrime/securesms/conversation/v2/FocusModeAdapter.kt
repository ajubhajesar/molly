package org.thoughtcrime.securesms.conversation.v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.thoughtcrime.securesms.conversation.ConversationMessage

/**
 * AJ fork: minimal adapter for Focus Mode — plain text, center-aligned,
 * with «outgoing» and incoming< markers. No bubbles, no avatars.
 */
class FocusModeAdapter : ListAdapter<FocusModeAdapter.FocusItem, FocusModeAdapter.ViewHolder>(DIFF) {

  data class FocusItem(val text: String, val isOutgoing: Boolean)

  class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val textView: TextView = view as TextView
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val tv = TextView(parent.context).apply {
      layoutParams = RecyclerView.LayoutParams(
        RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.WRAP_CONTENT
      )
      textSize = 16f
      setTextColor(0xFFEEEEEE.toInt())
      textAlignment = View.TEXT_ALIGNMENT_CENTER
      setPadding(48, 10, 48, 10)
      typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
      letterSpacing = 0.02f
      setLineSpacing(0f, 1.3f)
    }
    return ViewHolder(tv)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = getItem(position)
    holder.textView.text = if (item.isOutgoing) "«${item.text}»" else "${item.text} <"
    // Fade older messages: newest = full opacity, fades toward top
    val total = itemCount
    val fromBottom = total - 1 - position
    holder.textView.alpha = when {
      fromBottom <= 3  -> 1.0f
      fromBottom <= 8  -> 0.7f
      fromBottom <= 15 -> 0.45f
      else             -> 0.2f
    }
  }

  companion object {
    fun fromConversationMessages(messages: List<ConversationMessage>): List<FocusItem> =
      messages
        .filter { it.messageRecord.body.isNotBlank() && !it.messageRecord.isUpdate }
        .map { FocusItem(it.messageRecord.body.trim(), it.messageRecord.isOutgoing) }

    private val DIFF = object : DiffUtil.ItemCallback<FocusItem>() {
      override fun areItemsTheSame(a: FocusItem, b: FocusItem) = a == b
      override fun areContentsTheSame(a: FocusItem, b: FocusItem) = a == b
    }
  }
}

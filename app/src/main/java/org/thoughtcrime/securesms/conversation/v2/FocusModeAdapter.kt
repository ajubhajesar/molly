package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Typeface
import android.util.TypedValue
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
 * All text is CENTER aligned within its view.
 * Position is shifted slightly right (outgoing) or slightly left (incoming)
 * via asymmetric margins on the item's LayoutParams.
 *
 * Dim effect is handled by a static gradient overlay in the layout —
 * no per-item alpha needed.
 *
 * Toggle: isLeftRightMode = true → same shift but larger (more L/R feel).
 */
class FocusModeAdapter : ListAdapter<FocusModeAdapter.FocusItem, FocusModeAdapter.ViewHolder>(DIFF) {

  data class FocusItem(val text: String, val isOutgoing: Boolean)

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
      alpha = 1f
      typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
      letterSpacing = 0.02f
      setLineSpacing(0f, 1.4f)
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
    return ViewHolder(tv)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = getItem(position)
    val ctx = holder.tv.context

    // Convert dp → px for margins
    fun dp(value: Int) = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    val params = holder.tv.layoutParams as RecyclerView.LayoutParams

    holder.tv.text = if (item.isOutgoing) "${item.text}  \u003C" else "\u003E  ${item.text}"
    holder.tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
    holder.tv.setPadding(dp(16), dp(10), dp(16), dp(10))

    // Shift position via margins: outgoing → right of center, incoming → left of center
    if (isLeftRightMode) {
      if (item.isOutgoing) {
        params.marginStart = dp(80); params.marginEnd = dp(8)
      } else {
        params.marginStart = dp(8); params.marginEnd = dp(80)
      }
    } else {
      // subtle offset
      if (item.isOutgoing) {
        params.marginStart = dp(48); params.marginEnd = dp(8)
      } else {
        params.marginStart = dp(8); params.marginEnd = dp(48)
      }
    }
    holder.tv.layoutParams = params
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

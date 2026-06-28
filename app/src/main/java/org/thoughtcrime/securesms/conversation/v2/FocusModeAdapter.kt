package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Typeface
import android.util.TypedValue
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
 *
 * KEY FIX: use gravity=CENTER_HORIZONTAL (NOT textAlignment=CENTER).
 * textAlignment=CENTER ignores padding and centers against full view width.
 * gravity=CENTER_HORIZONTAL centers within the padded area — so asymmetric
 * padding actually shifts the visual position of the text.
 *
 * Outgoing (right of center): large paddingStart, small paddingEnd
 * Incoming (left of center):  small paddingStart, large paddingEnd
 * Both look centered within their column but columns are offset from each other.
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
      typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
      letterSpacing = 0.02f
      setLineSpacing(0f, 1.4f)
      // CRITICAL: gravity not textAlignment — gravity respects padding bounds
      gravity = Gravity.CENTER_HORIZONTAL
    }
    return ViewHolder(tv)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = getItem(position)
    val ctx = holder.tv.context
    fun dp(v: Int) = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    holder.tv.text = if (item.isOutgoing) "${item.text}  \u003C" else "\u003E  ${item.text}"

    // Do NOT set textAlignment — let gravity handle it
    // Asymmetric padding: large on opposite side pushes text toward desired side
    val shift = if (isLeftRightMode) dp(110) else dp(60)
    val near  = dp(16)
    val top   = dp(10); val bot = dp(10)

    if (item.isOutgoing) {
      // paddingStart large → text pushed RIGHT of center
      holder.tv.setPadding(shift, top, near, bot)
    } else {
      // paddingEnd large → text pushed LEFT of center
      holder.tv.setPadding(near, top, shift, bot)
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

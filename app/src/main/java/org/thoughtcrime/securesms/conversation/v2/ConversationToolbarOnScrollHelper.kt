package org.thoughtcrime.securesms.conversation.v2

import android.view.View
import androidx.annotation.ColorRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.R as MaterialR
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.Material3OnScrollHelper
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper

/**
 * Scroll helper to manage the color state of the top bar and status bar.
 */
class ConversationToolbarOnScrollHelper(
  private val toolbarActivity: FragmentActivity,
  toolbarBackground: View,
  private val wallpaperProvider: () -> ChatWallpaper?,
  lifecycleOwner: LifecycleOwner,
  private val incognito: Boolean = false
) : Material3OnScrollHelper(
  activity = toolbarActivity,
  views = listOf(toolbarBackground),
  lifecycleOwner = lifecycleOwner,
  setStatusBarColor = {}
) {
  private val isAmoled: Boolean
    get() = TextSecurePreferences.isAmoledEnabled(toolbarActivity) && DynamicTheme.isDarkTheme(toolbarActivity)

  override val activeColorSet: ColorSet
    = if (incognito) ColorSet.from(toolbarActivity, R.color.conversation_toolbar_color_incognito) else if (isAmoled) ColorSet.from(toolbarActivity, R.color.core_black) else ColorSet.from(toolbarActivity, getActiveToolbarColor(wallpaperProvider() != null))

  override val inactiveColorSet: ColorSet
    = if (incognito) ColorSet.from(toolbarActivity, R.color.conversation_toolbar_color_incognito) else if (isAmoled) ColorSet.from(toolbarActivity, R.color.core_black) else ColorSet.from(toolbarActivity, getInactiveToolbarColor(wallpaperProvider() != null))

  @ColorRes
  private fun getActiveToolbarColor(hasWallpaper: Boolean): Int {
    return if (hasWallpaper) R.color.conversation_toolbar_color_wallpaper_scrolled else MaterialR.attr.colorSurfaceContainer
  }

  @ColorRes
  private fun getInactiveToolbarColor(hasWallpaper: Boolean): Int {
    return if (hasWallpaper) R.color.conversation_toolbar_color_wallpaper else MaterialR.attr.colorSurface
  }
}

package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.thoughtcrime.securesms.R;
import org.signal.core.ui.util.ThemeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * AJ fork: "text" presence style - same pill treatment as the bubble style's "In chat" state
 * (literally the same tintable_pill_bg drawable and the same wallpaper-aware tint logic as
 * ConversationTypingView's typing_count view), but as its own standalone floating indicator.
 * The old bubble style is untouched - this is a fully separate, additive style.
 *
 * Two states, deliberately different in kind, not just content:
 *  - present-only: "In chat", a single static TextView, no animation at all - exact copy of
 *    what bubble style already shows for this state.
 *  - typing: "Typing" with a fade+scale wave walking left to right across its letters
 *    (mirrors TypingIndicatorView's exact curve - same MIN_ALPHA/MIN_SCALE/duration - just
 *    generalized from 3 fixed dots to N characters), followed by a separate un-animated
 *    suffix that cycles . / .. / ... on a fixed timer.
 */
public class PresenceWaveTextView extends LinearLayout {

  private static final long  CHAR_STAGGER       = 80;   // ms between adjacent characters starting their pulse
  private static final long  CHAR_DURATION      = 600;  // matches TypingIndicatorView's DOT_DURATION
  private static final float MIN_ALPHA          = 0.4f;
  private static final float MIN_SCALE          = 0.85f; // less aggressive than dots' 0.75f - full-size letters shrinking that much read as jumpy rather than a smooth wave
  private static final long  DOT_COUNT_INTERVAL = 500;  // how long each of . / .. / ... shows before advancing

  private static final String PRESENT_TEXT = "In chat";
  private static final String TYPING_TEXT  = "Typing";

  // AJ fork: live-typing preview truncation - keeps the pill from growing unbounded as the
  // peer's draft gets longer, biased toward showing the most-recently-typed tail since that's
  // what tells you where they currently are in the sentence.
  private static final int MAX_LIVE_PREVIEW_CHARS = 80;

  private boolean isActive;
  private boolean isTyping;
  private boolean isLive;
  // AJ fork: style index 5 (the 6th style, "live - smooth") - deliberately separate
  // fields/flag/view from the style-4 live path above. Nothing in showLiveText() or isLive is
  // touched by this - if the smooth path has a bug, switching back to style 4 exercises code
  // this change never modified.
  private boolean isSmoothLive;
  private TextView smoothLiveTextView;
  private boolean hasWallpaper;
  private long    startTime;
  private long    charCycleDuration;

  private final List<TextView> charViews = new ArrayList<>();
  private TextView dotsView;

  public PresenceWaveTextView(Context context) {
    super(context);
    initialize();
  }

  public PresenceWaveTextView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  private void initialize() {
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setWillNotDraw(false);

    setBackgroundResource(R.drawable.tintable_pill_bg);
    int paddingH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
    int paddingV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, getResources().getDisplayMetrics());
    setPadding(paddingH, paddingV, paddingH, paddingV);
    applyTint();

    rebuild(false);
  }

  /** AJ fork: same wallpaper-aware tint logic as ConversationTypingView's typing_count pill. */
  public void setHasWallpaper(boolean hasWallpaper) {
    if (this.hasWallpaper == hasWallpaper) {
      return;
    }
    this.hasWallpaper = hasWallpaper;
    applyTint();
  }

  private void applyTint() {
    int color = hasWallpaper
        ? ContextCompat.getColor(getContext(), R.color.conversation_item_recv_bubble_color_wallpaper)
        : ThemeUtil.getThemedColor(getContext(), R.attr.conversation_item_recv_bubble_color_normal);
    getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
  }

  /** Rebuilds child views only when the state actually changes - avoids tearing down/recreating every render frame. */
  public void setTyping(boolean typing) {
    if (!charViews.isEmpty() && this.isTyping == typing) {
      return;
    }
    this.isTyping = typing;
    rebuild(typing);
  }

  private void rebuild(boolean typing) {
    removeAllViews();
    charViews.clear();
    dotsView = null;
    isLive = false;
    isSmoothLive = false;

    if (!typing) {
      // AJ fork: present-only - exact copy of bubble style's "In chat" treatment: one plain
      // static TextView, no per-character split, no animation.
      TextView staticText = makeTextView(PRESENT_TEXT);
      addView(staticText);
      charViews.add(staticText);
      staticText.setAlpha(1f);
      staticText.setScaleX(1f);
      staticText.setScaleY(1f);
      return;
    }

    for (int i = 0; i < TYPING_TEXT.length(); i++) {
      TextView charView = makeTextView(String.valueOf(TYPING_TEXT.charAt(i)));
      addView(charView);
      charViews.add(charView);
    }

    dotsView = makeTextView("");
    addView(dotsView);

    charCycleDuration = CHAR_STAGGER * Math.max(charViews.size() - 1, 0) + CHAR_DURATION;
  }

  private TextView makeTextView(String text) {
    TextView tv = new TextView(getContext());
    tv.setText(text);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    tv.setTextColor(ThemeUtil.getThemedColor(getContext(), R.attr.signal_text_secondary));
    return tv;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (!isActive || !isTyping) {
      super.onDraw(canvas);
      return;
    }

    long elapsed = System.currentTimeMillis() - startTime;

    for (int i = 0; i < charViews.size(); i++) {
      renderChar(charViews.get(i), elapsed % charCycleDuration, i * CHAR_STAGGER);
    }

    if (dotsView != null) {
      long dotElapsed  = elapsed % (DOT_COUNT_INTERVAL * 3);
      int  dotCount    = (int) (dotElapsed / DOT_COUNT_INTERVAL) + 1;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < dotCount; i++) {
        sb.append('.');
      }
      dotsView.setText(sb.toString());
    }

    super.onDraw(canvas);
    postInvalidate();
  }

  private void renderChar(TextView charView, long timeInCycle, long start) {
    long end  = start + CHAR_DURATION;
    long peak = start + (CHAR_DURATION / 2);

    if (timeInCycle < start || timeInCycle > end) {
      renderDefault(charView);
    } else if (timeInCycle < peak) {
      renderFadeIn(charView, timeInCycle, start);
    } else {
      renderFadeOut(charView, timeInCycle, peak);
    }
  }

  private void renderDefault(TextView charView) {
    charView.setAlpha(MIN_ALPHA);
    charView.setScaleX(MIN_SCALE);
    charView.setScaleY(MIN_SCALE);
  }

  private void renderFadeIn(TextView charView, long timeInCycle, long fadeInStart) {
    float percent = (float) (timeInCycle - fadeInStart) / (CHAR_DURATION / 2);
    charView.setAlpha(MIN_ALPHA + (1 - MIN_ALPHA) * percent);
    charView.setScaleX(MIN_SCALE + (1 - MIN_SCALE) * percent);
    charView.setScaleY(MIN_SCALE + (1 - MIN_SCALE) * percent);
  }

  private void renderFadeOut(TextView charView, long timeInCycle, long fadeOutStart) {
    float percent = (float) (timeInCycle - fadeOutStart) / (CHAR_DURATION / 2);
    charView.setAlpha(1 - (1 - MIN_ALPHA) * percent);
    charView.setScaleX(1 - (1 - MIN_SCALE) * percent);
    charView.setScaleY(1 - (1 - MIN_SCALE) * percent);
  }

  /** AJ fork: only meaningful during typing - present-only has nothing to animate. */
  public void startAnimation() {
    if (!isTyping) {
      return;
    }
    isActive  = true;
    startTime = System.currentTimeMillis();
    postInvalidate();
  }

  public void stopAnimation() {
    isActive = false;
  }

  /** True only while the typing-state wave loop is actually running. */
  public boolean isActive() {
    return isActive && isTyping;
  }

  /**
   * AJ fork: live-typing style - renders the peer's actual draft buffer as it changes. No wave
   * animation here on purpose: animating every keystroke reads as visual noise, not signal -
   * the changing text itself is already the indicator. Truncates from the start of the string
   * so the tail (the part they just typed) always stays visible.
   */
  public void showLiveText(@NonNull String liveText) {
    isLive   = true;
    isActive = false;

    removeAllViews();
    charViews.clear();
    dotsView = null;

    String display = liveText.isEmpty() ? "…" : liveText;
    if (display.length() > MAX_LIVE_PREVIEW_CHARS) {
      display = "…" + display.substring(display.length() - MAX_LIVE_PREVIEW_CHARS);
    }

    TextView staticText = makeTextView(display);
    addView(staticText);
    charViews.add(staticText);
    staticText.setAlpha(1f);
    staticText.setScaleX(1f);
    staticText.setScaleY(1f);
  }

  public boolean isShowingLive() {
    return isLive || isSmoothLive;
  }

  /**
   * AJ fork: style index 5 ("live - smooth"). Same idea as showLiveText() - render the peer's
   * draft, no wave animation - but fixes the two things that made the style-4 version stutter:
   *  - reuses one TextView across every update (setText() only) instead of removeAllViews() +
   *    a new TextView every call, so there's no pop/flicker from view teardown mid-typing.
   *  - truncation is native TextView ellipsize(START) against a pixel maxWidth, not a manual
   *    char-count substring recomputed every call - the visible window shifts continuously as
   *    Android lays it out, instead of jumping because our own index math changed.
   * The mode-entry fade (switching in from "In chat" or from hidden) is the one animation added
   * here - deliberately not fading on every single keystroke update, since a fade cycling every
   * ~200ms would read as more flicker, not less. The text changing is already the signal.
   */
  public void showLiveTextSmooth(@NonNull String liveText) {
    if (!isSmoothLive) {
      isActive     = false;
      isLive       = false;
      removeAllViews();
      charViews.clear();
      dotsView     = null;
      isSmoothLive = true;

      if (smoothLiveTextView == null) {
        smoothLiveTextView = makeTextView("");
        smoothLiveTextView.setSingleLine(true);
        smoothLiveTextView.setEllipsize(TextUtils.TruncateAt.START);
        int maxWidthPx = getResources().getDimensionPixelSize(R.dimen.media_bubble_max_width);
        smoothLiveTextView.setMaxWidth(maxWidthPx);
      }

      addView(smoothLiveTextView);
      smoothLiveTextView.setScaleX(1f);
      smoothLiveTextView.setScaleY(1f);
      smoothLiveTextView.animate().cancel();
      smoothLiveTextView.setAlpha(0f);
      smoothLiveTextView.animate().alpha(1f).setDuration(150).start();
    }

    smoothLiveTextView.setText(liveText);
  }
}

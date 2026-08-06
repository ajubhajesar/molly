package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import org.thoughtcrime.securesms.R;

import java.util.ArrayList;
import java.util.List;

/**
 * AJ fork: "text" presence style - no avatar/bubble chrome, just words. Mirrors
 * TypingIndicatorView's exact fade+scale wave curve (same shape of animation, same
 * self-driving onDraw()+postInvalidate() render loop) but generalized from 3 fixed dot Views
 * to N per-character TextViews, so an arbitrary string can wave left-to-right instead of just
 * 3 dots. The old bubble style (ConversationTypingView/TypingIndicatorView) is untouched -
 * this is a fully separate, additive style living in its own floating slot.
 *
 * Two states:
 *  - present-only: "In chat", every letter doing the wave.
 *  - typing: "Typing" doing the same letter wave, followed by a separate un-animated suffix
 *    that cycles through . / .. / ... on a fixed timer - a discrete count change, not part of
 *    the continuous wave, so it's kept as its own TextView rather than forced into the same
 *    per-character animation loop as the word itself.
 */
public class PresenceWaveTextView extends LinearLayout {

  private static final long  CHAR_STAGGER      = 80;   // ms between adjacent characters starting their pulse
  private static final long  CHAR_DURATION     = 600;  // matches TypingIndicatorView's DOT_DURATION
  private static final float MIN_ALPHA         = 0.4f;
  private static final float MIN_SCALE         = 0.85f; // less aggressive than dots' 0.75f - full-size letters shrinking that much read as jumpy rather than a smooth wave
  private static final long  DOT_COUNT_INTERVAL = 500;  // how long each of . / .. / ... shows before advancing

  private static final String PRESENT_TEXT = "In chat";
  private static final String TYPING_TEXT  = "Typing";

  private boolean isActive;
  private boolean isTyping;
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
    rebuild(PRESENT_TEXT);
  }

  /** Rebuilds child views only when the state actually changes - avoids tearing down/recreating every render frame. */
  public void setTyping(boolean typing) {
    if (!charViews.isEmpty() && this.isTyping == typing) {
      return;
    }
    this.isTyping = typing;
    rebuild(typing ? TYPING_TEXT : PRESENT_TEXT);
  }

  private void rebuild(String text) {
    removeAllViews();
    charViews.clear();

    for (int i = 0; i < text.length(); i++) {
      TextView charView = makeTextView(String.valueOf(text.charAt(i)));
      addView(charView);
      charViews.add(charView);
    }

    if (isTyping) {
      dotsView = makeTextView("");
      addView(dotsView);
    } else {
      dotsView = null;
    }

    charCycleDuration = CHAR_STAGGER * Math.max(charViews.size() - 1, 0) + CHAR_DURATION;
  }

  private TextView makeTextView(String text) {
    TextView tv = new TextView(getContext());
    tv.setText(text);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    tv.setTextColor(MaterialColors.getColor(this, R.attr.signal_text_secondary, Color.GRAY));
    return tv;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (!isActive) {
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

  public void startAnimation() {
    isActive  = true;
    startTime = System.currentTimeMillis();
    postInvalidate();
  }

  public void stopAnimation() {
    isActive = false;
  }

  public boolean isActive() {
    return isActive;
  }
}

package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** One owned hierarchy for sheets, selection menus and confirmations. */
final class DesignDialog extends AlertDialog {
  final Map<Integer, Button> actions = new HashMap<>();
  private final Builder spec;
  boolean sheet;
  private Runnable cancelRequest;
  private View cancelSurface;
  void setCancelRequest(Runnable request) { cancelRequest = request; }
  @Override public void cancel() {
    if (cancelSurface != null) {
      cancelSurface.animate().cancel();
      cancelSurface.setTranslationY(0);
    }
    if (cancelRequest != null) cancelRequest.run(); else super.cancel();
  }
  private ScrollView wholeContentScroll;

  DesignDialog(Builder spec) {
    super(spec.context);
    this.spec = spec;
    setCancelable(spec.cancelable);
  }

  private int dp(float n) {
    return AppViews.dp(getContext(), n);
  }

  boolean hasRoundedWindow() {
    return spec.roundedWindow;
  }

  private TextView text(CharSequence value, int size, int color, boolean bold) {
    TextView t = new TextView(getContext());
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
    return t;
  }

  protected void onCreate(Bundle state) {
    sheet = !spec.centered && (spec.items != null || spec.view != null);
    boolean destructive = spec.destructive;
    int highlight = destructive ? (spec.dark ? 0xFFFF817C : 0xFFC73232) : spec.accent;
    LinearLayout shell = new LinearLayout(getContext());
    cancelSurface = shell;
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setPadding(dp(22), sheet ? dp(10) : dp(24), dp(22), dp(20));
    GradientDrawable material =
        AppViews.round(HealthDesign.card(spec.dark), dp(HealthDesign.SHEET));
    shell.setBackground(material);
    if (spec.roundedWindow) shell.setClipToOutline(true);
    if (sheet) {
      View handle = new View(getContext());
      handle.setBackground(AppViews.round(spec.dark ? 0x806F697F : 0x507D778A, dp(2)));
      LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(dp(34), dp(4));
      h.gravity = Gravity.CENTER_HORIZONTAL;
      h.bottomMargin = dp(20);
      handle.setContentDescription("下滑关闭面板");
      final float[] touch = {0};
      shell.setOnTouchListener(
          (v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
              touch[0] = e.getRawY();
              return e.getY() < dp(36);
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
              v.setTranslationY(Math.max(0, e.getRawY() - touch[0]) * .6f);
              return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
              float delta = e.getRawY() - touch[0];
              if (e.getActionMasked() == MotionEvent.ACTION_UP && delta > dp(80)) cancel();
              else
                v.animate()
                    .translationY(0)
                    .setDuration(HealthDesign.motion(getContext()) ? 180 : 0)
                    .start();
              return true;
            }
            return false;
          });
      shell.addView(handle, h);
    } else {
      TextView badge = text("", 20, highlight, false);
      Visuals.Icon icon =
          new Visuals.Icon(
              spec.iconKind, highlight);
      icon.setBounds(0, 0, dp(25), dp(25));
      badge.setCompoundDrawables(null, icon, null, null);
      badge.setGravity(Gravity.CENTER);
      badge.setPadding(0, dp(12), 0, 0);
      badge.setBackground(
          AppViews.round(destructive ? (spec.dark ? 0xFF4A3037 : 0xFFFBE9E8) : spec.soft, dp(16)));
      LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(dp(50), dp(50));
      b.gravity = Gravity.START;
      b.bottomMargin = dp(16);
      shell.addView(badge, b);
    }
    if (spec.heading != null) {
      TextView title = text(spec.heading, sheet ? 23 : 24, spec.ink, true);
      title.setGravity(Gravity.START);
      title.setPadding(0, 0, 0, dp(14));
      shell.addView(title);
    }
    final int detailHeaderCount = shell.getChildCount();
    LinearLayout content = new LinearLayout(getContext());
    content.setOrientation(LinearLayout.VERTICAL);
    if (spec.message != null) {
      TextView message = text(spec.message, 14, spec.muted, false);
      message.setGravity(Gravity.START);
      message.setPadding(0, 0, 0, dp(16));
      content.addView(message);
    }
    if (spec.view != null) {
      if (spec.view.getParent() != null) ((ViewGroup) spec.view.getParent()).removeView(spec.view);
      content.addView(spec.view, new LinearLayout.LayoutParams(-1, -2));
    }
    if (spec.items != null)
      for (int i = 0; i < spec.items.length; i++) {
        final int index = i;
        Button row = makeButton(spec.items[i], spec.checked == i, false, highlight);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTextColor(spec.checked == i ? highlight : spec.ink);
        row.setBackground(
            AppViews.button(
                spec.checked == i ? spec.soft : Color.TRANSPARENT,
                highlight,
                dp(HealthDesign.BUTTON)));
        if (spec.checked == i) {
          Visuals.Icon check = new Visuals.Icon("check", highlight);
          check.setBounds(0, 0, dp(20), dp(20));
          row.setCompoundDrawables(null, null, check, null);
        }
        row.setSelected(spec.checked == i);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        content.addView(row, lp);
        row.setOnClickListener(
            v -> {
              if (spec.choice != null) spec.choice.onClick(this, index);
              if (spec.checked < 0) dismiss();
            });
      }
    DetailBodyScroll detailBody = null;
    if (spec.adaptiveDetail) {
      detailBody = new DetailBodyScroll(getContext());
      detailBody.setVerticalScrollBarEnabled(false);
      detailBody.addView(content);
      shell.addView(detailBody, new LinearLayout.LayoutParams(-1, -2));
    } else if (spec.wholeContentScrollable) {
      shell.addView(content, new LinearLayout.LayoutParams(-1, -2));
    } else {
      ScrollView scroll =
          new ScrollView(getContext()) {
            protected void onMeasure(int w, int h) {
              int max = (int) (getResources().getDisplayMetrics().heightPixels * .57f);
              if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED)
                max = Math.min(max, MeasureSpec.getSize(h));
              super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
          };
      scroll.setVerticalScrollBarEnabled(false);
      scroll.addView(content);
      shell.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
    }
    LinearLayout buttons = new LinearLayout(getContext());
    boolean stack = spec.verticalActions || !sheet || (!spec.adaptiveDetail
        && getContext().getResources().getConfiguration().fontScale > 1.3f);
    buttons.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
    buttons.setGravity(Gravity.CENTER_VERTICAL);
    buttons.setPadding(0, dp(18), 0, 0);
    for (int which :
        spec.negativeButtonFirst
            ? new int[] {BUTTON_NEGATIVE, BUTTON_POSITIVE, BUTTON_NEUTRAL}
            : sheet
                ? new int[] {BUTTON_NEGATIVE, BUTTON_NEUTRAL, BUTTON_POSITIVE}
                : new int[] {BUTTON_POSITIVE, BUTTON_NEGATIVE, BUTTON_NEUTRAL})
      if (spec.labels.containsKey(which)) {
        Button button =
            makeButton(spec.labels.get(which), false, which == BUTTON_POSITIVE, highlight);
        if (destructive && which == BUTTON_NEGATIVE) button.setTextColor(spec.accent);
        actions.put(which, button);
        LinearLayout.LayoutParams lp =
            stack
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, dp(52), 1);
        if (buttons.getChildCount() > 0) {
          if (stack) lp.topMargin = dp(8);
          else lp.leftMargin = dp(8);
        }
        buttons.addView(button, lp);
        button.setOnClickListener(
            v -> {
              DialogInterface.OnClickListener handler = spec.handlers.get(which);
              if (handler != null) handler.onClick(this, which);
              dismiss();
            });
      }
    if (spec.negativeButtonFirst
        && actions.containsKey(BUTTON_NEGATIVE)
        && actions.containsKey(BUTTON_POSITIVE)) {
      Button first = actions.get(BUTTON_NEGATIVE);
      Button next = actions.get(BUTTON_POSITIVE);
      first.setId(View.generateViewId());
      next.setId(View.generateViewId());
      first.setNextFocusForwardId(next.getId());
    }
    if (!spec.labels.isEmpty()) shell.addView(buttons);
    if (spec.adaptiveDetail) {
      wholeContentScroll =
          new AdaptiveDetailScroll(getContext(), shell, detailBody, content, buttons, detailHeaderCount);
      wholeContentScroll.setVerticalScrollBarEnabled(true);
      wholeContentScroll.setVerticalFadingEdgeEnabled(true);
      wholeContentScroll.setFadingEdgeLength(dp(12));
      wholeContentScroll.setBackground(material);
      wholeContentScroll.setClipToOutline(true);
      shell.setBackground(null);
      wholeContentScroll.addView(shell, new FrameLayout.LayoutParams(-1, -2));
      setView(wholeContentScroll, 0, 0, 0, 0);
    } else if (spec.wholeContentScrollable) {
      wholeContentScroll =
          new ScrollView(getContext()) {
            protected void onMeasure(int w, int h) {
              int max = Math.max(0, getResources().getDisplayMetrics().heightPixels - dp(32));
              try {
                WindowManager manager =
                    (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                WindowMetrics metrics = manager.getCurrentWindowMetrics();
                Insets insets =
                    metrics
                        .getWindowInsets()
                        .getInsets(
                            WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout()
                                | WindowInsets.Type.ime());
                max = Math.max(0, metrics.getBounds().height() - insets.top - insets.bottom - dp(32));
              } catch (RuntimeException ignored) {
                // Parent measurement still limits the fallback when window metrics are unavailable.
              }
              if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED)
                max = Math.min(max, MeasureSpec.getSize(h));
              super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
          };
      wholeContentScroll.setVerticalScrollBarEnabled(true);
      wholeContentScroll.setVerticalFadingEdgeEnabled(true);
      wholeContentScroll.setFadingEdgeLength(dp(12));
      wholeContentScroll.setBackground(material);
      wholeContentScroll.setClipToOutline(true);
      shell.setBackground(null);
      wholeContentScroll.addView(shell, new FrameLayout.LayoutParams(-1, -2));
      setView(wholeContentScroll, 0, 0, 0, 0);
    } else setView(shell, 0, 0, 0, 0);
    super.onCreate(state);
  }

  private Button makeButton(CharSequence label, boolean selected, boolean primary, int highlight) {
    Button b = new Button(getContext());
    b.setAllCaps(false);
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setTranslationZ(0);
    b.setText(label);
    b.setTextSize(spec.actionTextSizeSp);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setMinHeight(dp(52));
    b.setMinimumHeight(dp(52));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(12), dp(10), dp(12), dp(10));
    b.setTextColor(primary ? (spec.dark ? 0xFF10151E : Color.WHITE) : highlight);
    b.setBackground(
        AppViews.button(
            primary ? highlight : HealthDesign.well(spec.dark),
            highlight,
            dp(HealthDesign.BUTTON)));
    GlassEffects.press(b);
    return b;
  }

  protected void onStart() {
    super.onStart();
    GlassEffects.decorate(this, spec.dark, spec.accent);
    Window w = getWindow();
    if (w != null) {
      w.setGravity(sheet ? Gravity.BOTTOM : Gravity.CENTER);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      WindowManager.LayoutParams p = w.getAttributes();
      p.width =
          Math.min(
              dp(sheet ? 520 : 380),
              getContext().getResources().getDisplayMetrics().widthPixels - dp(sheet ? 16 : 40));
      p.height = -2;
      p.y = dp(sheet ? 10 : 0);
      w.setAttributes(p);
    }
  }

  public Button getButton(int which) {
    return actions.get(which);
  }

  void focusSafeAction(View action) {
    action.requestFocus();
    if (wholeContentScroll != null) wholeContentScroll.post(() -> wholeContentScroll.scrollTo(0, 0));
  }

  static boolean detailNeedsWholeScroll(int availableHeight, int fixedHeight, int requiredBodyHeight) {
    return availableHeight <= 0 || fixedHeight < 0 || requiredBodyHeight <= 0
        || (long) availableHeight - fixedHeight < requiredBodyHeight;
  }

  private static int nonnegativePosition(long value) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
  }

  static int detailBodyAnchor(boolean whole, int outerY, int outerPadding, int bodyY,
      int bodyPadding, int contentStart, int contentTop) {
    return nonnegativePosition(whole ? (long) outerY + outerPadding - contentStart
        : (long) bodyY + bodyPadding - contentTop);
  }

  static int detailScrollForAnchor(boolean whole, boolean atTop, int bodyAnchor, int outerPadding,
      int bodyPadding, int contentStart, int contentTop) {
    if (atTop) return 0;
    return nonnegativePosition(whole ? (long) contentStart + bodyAnchor - outerPadding
        : (long) contentTop + bodyAnchor - bodyPadding);
  }

  /** The inner scroller owns gestures only while the detail's actions are fixed. */
  private final class DetailBodyScroll extends ScrollView {
    boolean whole = true;
    int heightLimit;

    DetailBodyScroll(Context context) { super(context); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int requested = whole
          ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
          : MeasureSpec.makeMeasureSpec(Math.max(0, heightLimit), MeasureSpec.AT_MOST);
      super.onMeasure(widthSpec, requested);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
      return !whole && super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
      return !whole && super.onTouchEvent(event);
    }
  }

  /** Only opted-in exercise details use this measured, single-hierarchy layout. */
  private final class AdaptiveDetailScroll extends ScrollView {
    private final LinearLayout shell;
    private final DetailBodyScroll body;
    private final LinearLayout content;
    private final LinearLayout buttons;
    private final int headerCount;
    private boolean measuredWhole = true, appliedWhole = true, laidOut, touching, deferred;
    private boolean heightKnown;
    private ReadingAnchor pendingAnchor;

    private final class ReadingAnchor {
      View leaf, focused;
      int fallbackY, leafOffset, character = -1, lineOffset;
      int contentWidth, contentHeight, bodyHeight, viewportHeight, contentStart;
      boolean atTop, focusedWasVisible;
    }

    AdaptiveDetailScroll(Context context, LinearLayout shell, DetailBodyScroll body,
        LinearLayout content, LinearLayout buttons, int headerCount) {
      super(context);
      this.shell = shell;
      this.body = body;
      this.content = content;
      this.buttons = buttons;
      this.headerCount = headerCount;
      body.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
        if (laidOut) pendingAnchor = null;
      });
      setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
        if (laidOut) pendingAnchor = null;
      });
    }

    private ReadingAnchor captureAnchor() {
      ReadingAnchor anchor = new ReadingAnchor();
      anchor.atTop = (appliedWhole ? getScrollY() : body.getScrollY()) == 0;
      int contentStart = shell.getTop() + body.getTop() + content.getTop();
      anchor.contentStart = contentStart;
      anchor.contentWidth = content.getWidth();
      anchor.contentHeight = content.getHeight();
      anchor.bodyHeight = body.getHeight();
      anchor.viewportHeight = getHeight();
      anchor.fallbackY = detailBodyAnchor(appliedWhole, getScrollY(), getPaddingTop(),
          body.getScrollY(), body.getPaddingTop(), contentStart, content.getTop());
      int localY = anchor.fallbackY;
      View leaf = content;
      while (leaf instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) leaf;
        View next = null;
        for (int i = 0; i < group.getChildCount(); i++) {
          View child = group.getChildAt(i);
          if (child.getVisibility() == View.VISIBLE && child.getBottom() > localY) {
            next = child;
            break;
          }
        }
        if (next == null) break;
        localY = Math.max(0, localY - next.getTop());
        leaf = next;
      }
      if (leaf != content && !(leaf instanceof ViewGroup)) {
        anchor.leaf = leaf;
        anchor.leafOffset = localY;
        if (leaf instanceof TextView) {
          TextView text = (TextView) leaf;
          android.text.Layout layout = text.getLayout();
          int textY = localY - text.getTotalPaddingTop();
          if (layout != null && layout.getLineCount() > 0 && textY >= 0) {
            int line = layout.getLineForVertical(Math.min(textY, Math.max(0, layout.getHeight() - 1)));
            anchor.character = layout.getLineStart(line);
            anchor.lineOffset = Math.max(0, textY - layout.getLineTop(line));
          }
        }
      }
      anchor.focused = findFocus();
      if (anchor.focused != null) {
        Rect visible = new Rect();
        anchor.focusedWasVisible = anchor.focused.getGlobalVisibleRect(visible)
            && visible.width() >= anchor.focused.getWidth()
            && visible.height() >= anchor.focused.getHeight();
      }
      return anchor;
    }

    private int resolveAnchor(ReadingAnchor anchor) {
      View leaf = anchor.leaf;
      if (leaf == null || leaf.getVisibility() != View.VISIBLE) return anchor.fallbackY;
      int offset = Math.min(anchor.leafOffset, Math.max(0, leaf.getHeight() - 1));
      if (anchor.character >= 0 && leaf instanceof TextView) {
        TextView text = (TextView) leaf;
        android.text.Layout layout = text.getLayout();
        if (layout != null && layout.getLineCount() > 0) {
          int character = Math.min(anchor.character, text.length());
          int line = layout.getLineForOffset(character);
          int lineHeight = layout.getLineBottom(line) - layout.getLineTop(line);
          offset = text.getTotalPaddingTop() + layout.getLineTop(line)
              + Math.min(anchor.lineOffset, Math.max(0, lineHeight - 1));
        }
      }
      int top = 0;
      View current = leaf;
      while (current != content && current.getParent() instanceof View) {
        top += current.getTop();
        current = (View) current.getParent();
      }
      return current == content ? Math.max(0, top + offset) : anchor.fallbackY;
    }

    private int availableHeight(int heightSpec) {
      int available = Math.max(0, getResources().getDisplayMetrics().heightPixels - dp(32));
      heightKnown = MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED;
      try {
        WindowManager manager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        WindowMetrics metrics = manager.getCurrentWindowMetrics();
        Insets insets = metrics.getWindowInsets().getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        available = Math.max(0, metrics.getBounds().height() - insets.top - insets.bottom - dp(32));
        heightKnown = true;
      } catch (RuntimeException ignored) {
        // Match the existing whole-dialog fallback; the parent still bounds measurement.
      }
      if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
        available = Math.min(available, MeasureSpec.getSize(heightSpec));
      return available;
    }

    private int measuredChromeChild(View child, int innerWidth) {
      if (child.getVisibility() == View.GONE) return 0;
      LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) child.getLayoutParams();
      int width = Math.max(0, innerWidth - p.leftMargin - p.rightMargin);
      int childWidth = p.width >= 0
          ? MeasureSpec.makeMeasureSpec(Math.min(width, p.width), MeasureSpec.EXACTLY)
          : MeasureSpec.makeMeasureSpec(width, p.width == LayoutParams.MATCH_PARENT
              ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
      int childHeight = p.height >= 0
          ? MeasureSpec.makeMeasureSpec(p.height, MeasureSpec.EXACTLY)
          : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
      child.measure(childWidth, childHeight);
      return child.getMeasuredHeight() + p.topMargin + p.bottomMargin;
    }

    private int minimumBodyHeight(View view) {
      if (view.getVisibility() != View.VISIBLE) return 0;
      int required = 0;
      if (view instanceof TextView) {
        TextView label = (TextView) view;
        required = label.getLineHeight() + label.getPaddingTop() + label.getPaddingBottom();
        if (view.isClickable()) required = Math.max(required, view.getMeasuredHeight());
      }
      if (view instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++)
          required = Math.max(required, minimumBodyHeight(group.getChildAt(i)));
      }
      return required;
    }

    private void fitButtons(int innerWidth) {
      int count = buttons.getChildCount();
      if (count == 0) return;
      int available = Math.max(0, innerWidth - buttons.getPaddingLeft()
          - buttons.getPaddingRight() - dp(8) * (count - 1));
      int each = available / count;
      boolean stack = spec.verticalActions;
      for (int i = 0; i < count; i++) {
        Button button = (Button) buttons.getChildAt(i);
        float textWidth = android.text.Layout.getDesiredWidth(button.getText(), button.getPaint());
        Paint.FontMetricsInt font = button.getPaint().getFontMetricsInt();
        int textHeight = font.descent - font.ascent + button.getPaddingTop() + button.getPaddingBottom();
        if (each < dp(48) || textWidth > each - button.getPaddingLeft() - button.getPaddingRight()
            || textHeight > dp(52)) stack = true;
      }
      boolean currentlyStacked = buttons.getOrientation() == LinearLayout.VERTICAL;
      if (touching && laidOut && stack != currentlyStacked) {
        deferred = true;
        return;
      }
      if (stack == currentlyStacked) return;
      buttons.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
      for (int i = 0; i < count; i++) {
        View button = buttons.getChildAt(i);
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) button.getLayoutParams();
        p.width = stack ? LayoutParams.MATCH_PARENT : 0;
        p.height = stack ? LayoutParams.WRAP_CONTENT : dp(52);
        p.weight = stack ? 0 : 1;
        p.leftMargin = !stack && i > 0 ? dp(8) : 0;
        p.topMargin = stack && i > 0 ? dp(8) : 0;
        button.setLayoutParams(p);
      }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      if (laidOut && !touching && pendingAnchor == null) pendingAnchor = captureAnchor();
      int available = availableHeight(heightSpec);
      int width = MeasureSpec.getSize(widthSpec);
      int innerWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight()
          - shell.getPaddingLeft() - shell.getPaddingRight());
      boolean whole = true;
      int remaining = 0;
      if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && innerWidth > 0) {
        fitButtons(innerWidth);
        int chrome = shell.getPaddingTop() + shell.getPaddingBottom();
        for (int i = 0; i < headerCount; i++) chrome += measuredChromeChild(shell.getChildAt(i), innerWidth);
        if (buttons.getParent() == shell) chrome += measuredChromeChild(buttons, innerWidth);
        content.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int required = Math.max(dp(48), minimumBodyHeight(content));
        remaining = Math.max(0, available - chrome);
        whole = !heightKnown || detailNeedsWholeScroll(available, chrome, required);
      }
      if (touching && laidOut && whole != appliedWhole) {
        deferred = true;
        whole = appliedWhole;
      }
      measuredWhole = whole;
      boolean bodyMeasureChanged = body.whole != whole
          || (!whole && body.heightLimit != remaining);
      body.whole = whole;
      body.heightLimit = remaining;
      if (bodyMeasureChanged) {
        // These fields affect measurement even when the framework's specs stay the same.
        body.forceLayout();
        shell.forceLayout();
      }
      super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      boolean switching = laidOut && appliedWhole != measuredWhole;
      ReadingAnchor anchor = pendingAnchor;
      pendingAnchor = null;
      super.onLayout(changed, left, top, right, bottom);
      boolean reflow = anchor != null && (switching || anchor.contentWidth != content.getWidth()
          || anchor.contentHeight != content.getHeight() || anchor.bodyHeight != body.getHeight()
          || anchor.viewportHeight != getHeight()
          || anchor.contentStart != shell.getTop() + body.getTop() + content.getTop());
      if (reflow && !touching) {
        int position = resolveAnchor(anchor);
        if (measuredWhole) {
          body.scrollTo(0, 0);
          int contentStart = shell.getTop() + body.getTop() + content.getTop();
          scrollTo(0, detailScrollForAnchor(true, anchor.atTop, position, getPaddingTop(),
              body.getPaddingTop(), contentStart, content.getTop()));
        } else {
          scrollTo(0, 0);
          int contentStart = shell.getTop() + body.getTop() + content.getTop();
          body.scrollTo(0, detailScrollForAnchor(false, anchor.atTop, position, getPaddingTop(),
              body.getPaddingTop(), contentStart, content.getTop()));
        }
        View focused = anchor.focused;
        if (anchor.focusedWasVisible && focused != null && focused != this && focused != body
            && focused.getWidth() > 0 && focused.getHeight() > 0) {
          Rect visible = new Rect();
          if (!focused.getGlobalVisibleRect(visible)
              || visible.width() < focused.getWidth() || visible.height() < focused.getHeight())
            focused.requestRectangleOnScreen(
                new Rect(0, 0, focused.getWidth(), focused.getHeight()), true);
        }
        if (switching) {
          // Stop old momentum from the final restored positions, not their previous origins.
          fling(0);
          body.fling(0);
        }
      }
      appliedWhole = measuredWhole;
      laidOut = true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) touching = true;
      try {
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_DOWN && !handled) touching = false;
        return handled;
      } finally {
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
          touching = false;
          if (deferred) {
            deferred = false;
            if (laidOut && isAttachedToWindow()) {
              pendingAnchor = captureAnchor();
              post(this::requestLayout);
            }
          }
        }
      }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
      return measuredWhole && super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
      return measuredWhole && super.onTouchEvent(event);
    }
  }

  static class Builder extends AlertDialog.Builder {
    final Context context;
    final boolean dark;
    final int ink, muted, accent, soft;
    CharSequence heading, message;
    View view;
    CharSequence[] items;
    int checked = -1;
    boolean cancelable = true;
    boolean wholeContentScrollable;
    boolean adaptiveDetail;
    boolean negativeButtonFirst;
    boolean verticalActions;
    int actionTextSizeSp = 14;
    boolean centered, destructive, roundedWindow;
    String iconKind = "info";
    DialogInterface.OnClickListener choice;
    final Map<Integer, CharSequence> labels = new HashMap<>();
    final Map<Integer, DialogInterface.OnClickListener> handlers = new HashMap<>();

    Builder(Context c, boolean d, int ink, int muted, int accent, int soft) {
      super(c);
      context = c;
      dark = d;
      this.ink = ink;
      this.muted = muted;
      this.accent = accent;
      this.soft = soft;
    }

    Builder setWholeContentScrollable() {
      wholeContentScrollable = true;
      return this;
    }

    Builder setAdaptiveDetail() {
      adaptiveDetail = true;
      return this;
    }

    /** Local caller style, used by initial layout and adaptive remeasurement alike. */
    Builder setVerticalActions(int textSizeSp) {
      verticalActions = true;
      actionTextSizeSp = textSizeSp;
      return this;
    }

    Builder setNegativeButtonFirst() {
      negativeButtonFirst = true;
      return this;
    }

    Builder setCentered() {
      centered = true;
      return this;
    }

    Builder setRoundedWindow() {
      roundedWindow = true;
      return this;
    }

    Builder setIconKind(String value) {
      iconKind = value;
      return this;
    }

    Builder setDestructive(boolean value) {
      destructive = value;
      return this;
    }

    @Override
    public Builder setTitle(CharSequence text) {
      heading = text;
      return this;
    }

    @Override
    public Builder setMessage(CharSequence text) {
      message = text;
      return this;
    }

    @Override
    public Builder setView(View v) {
      view = v;
      return this;
    }

    @Override
    public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener h) {
      labels.put(-1, text);
      handlers.put(-1, h);
      return this;
    }

    @Override
    public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener h) {
      labels.put(-2, text);
      handlers.put(-2, h);
      return this;
    }

    @Override
    public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener h) {
      labels.put(-3, text);
      handlers.put(-3, h);
      return this;
    }

    @Override
    public Builder setCancelable(boolean value) {
      cancelable = value;
      return this;
    }

    @Override
    public Builder setItems(CharSequence[] text, DialogInterface.OnClickListener h) {
      items = text;
      choice = h;
      checked = -1;
      return this;
    }

    @Override
    public Builder setSingleChoiceItems(
        CharSequence[] text, int selected, DialogInterface.OnClickListener h) {
      items = text;
      choice = h;
      checked = selected;
      return this;
    }

    @Override
    public AlertDialog create() {
      return new DesignDialog(this);
    }

    @Override
    public AlertDialog show() {
      AlertDialog dialog = create();
      dialog.show();
      return dialog;
    }
  }
}

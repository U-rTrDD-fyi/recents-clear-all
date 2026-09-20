package fyi.rtrdd.clearall;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Always-visible "Clear all" in Overview.
 *
 * Structure follows PixelXpert's ClearAllButtonMod, which is known to work on this
 * launcher. Two details are load-bearing and were learned the hard way:
 *
 *  - OverviewActionsView is WRAP_CONTENT, so a child added to it lands outside the
 *    parent's bounds and never receives touches. The parent is resized to MATCH_PARENT.
 *  - The MultiValueAlpha that fades the actions bar is attached to the inner
 *    action_buttons row, not to the root, so a child of the root is never hidden and
 *    shows up on the home screen. Visibility is instead driven off RecentsView's own.
 */
public class ClearAllModule implements IXposedHookLoadPackage {

    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String RECENTS_VIEW = "com.android.quickstep.views.RecentsView";
    private static final String ACTIONS_VIEW = "com.android.quickstep.views.OverviewActionsView";

    private static final String TAG = "[RecentsClearAll] ";

    /**
     * Horizontal placement of the floating variant: 0 = end (default), 1 = start,
     * 2 = centre. Large screens only -- compact panels put the button in the
     * Screenshot/Select row, where the row decides the position.
     */
    public static final String KEY_GRAVITY = "clear_all_button_gravity";

    private static final int END = 0, START = 1, CENTER = 2;

    private Object mRecentsView;
    private TextView mButton;
    private View mLookSource;
    /** Nothing is shown until the button has found its final parent and styling. */
    private boolean mSettled;
    private boolean mWantVisible;
    private boolean mRevealed;
    private boolean mRevealPending;

    /**
     * Measured on device: the stray flash lasted 193ms, the shortest genuine Overview
     * session 781ms. Anything comfortably above the former and below the latter works;
     * tune it live with
     *     settings put system clear_all_reveal_delay_ms <ms>
     * rather than rebuilding to try a number.
     */
    public static final String KEY_REVEAL_DELAY = "clear_all_reveal_delay_ms";
    private static final int DEFAULT_REVEAL_DELAY_MS = 200;
    private static final long FADE_MS = 120L;
    /** True from onGestureAnimationStart until onGestureAnimationEnd. */
    private volatile boolean mGestureActive;
    /** RecentsView#mContentAlpha: the overview-wide fade the stock button rides on. */
    private volatile float mContentAlpha = 1f;
    /** RecentsView#dismissAllTasks(View), resolved once against the declaring class. */
    private Method mDismissAllTasks;
    /** RecentsView#getTaskViewCount() -- the launcher's own "is Overview empty" answer. */
    private Method mTaskViewCount;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!LAUNCHER.equals(lpparam.packageName)) return;

        try {
            Class<?> recentsView = XposedHelpers.findClass(RECENTS_VIEW, lpparam.classLoader);
            Class<?> actionsView = XposedHelpers.findClass(ACTIONS_VIEW, lpparam.classLoader);

            // Resolve against RecentsView, where dismissAllTasks is declared -- the live
            // object is a LauncherRecentsView subclass, and callMethod() would infer the
            // parameter type from the argument (a TextView) and miss the View overload.
            mDismissAllTasks = XposedHelpers.findMethodBestMatch(
                    recentsView, "dismissAllTasks", View.class);
            mDismissAllTasks.setAccessible(true);

            mTaskViewCount = XposedHelpers.findMethodBestMatch(recentsView, "getTaskViewCount");
            mTaskViewCount.setAccessible(true);

            XposedHelpers.findAndHookConstructor(recentsView,
                    Context.class, android.util.AttributeSet.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mRecentsView = param.thisObject;
                        }
                    });

            // Overview's own visibility is one half of when to show; task count is the other.
            XposedHelpers.findAndHookMethod(recentsView, "setVisibility", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            updateVisibility();
                        }
                    });

            // A swipe-up drag passes through scale ~1.02 on its way to Overview, so
            // scale alone cannot tell "mid-gesture" from "arrived". These bracket the
            // gesture exactly. hookAllMethods avoids naming GroupedTaskInfo, which is
            // not on our compile classpath.
            // On the inner panel the button floats in the actions-view root, which is
            // never alpha-faded, so it has nothing to fade with and lingers opaque for
            // the first frames of the exit -- the scale ramp still reads ~1.0 there.
            // RecentsView pushes mContentAlpha into ClearAllButton.setContentAlpha(), so
            // mirroring it makes ours fade in lockstep with the stock one.
            XposedHelpers.findAndHookMethod(recentsView, "setContentAlpha", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] instanceof Float) {
                                mContentAlpha = (Float) param.args[0];
                            }
                            updateVisibility();
                        }
                    });

            XposedBridge.hookAllMethods(recentsView, "onGestureAnimationStart",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mGestureActive = true;
                            updateVisibility();
                        }
                    });
            XposedBridge.hookAllMethods(recentsView, "onGestureAnimationEnd",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mGestureActive = false;
                            updateVisibility();
                        }
                    });

            // Kept purely as a trigger to re-evaluate; the flag itself measured true on
            // the home screen, so it is not part of the gate.
            XposedHelpers.findAndHookMethod(recentsView, "setOverviewStateEnabled",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            updateVisibility();
                        }
                    });

            XposedHelpers.findAndHookMethod(actionsView, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            install((FrameLayout) param.thisObject);
                        }
                    });

            XposedBridge.log(TAG + "hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void install(FrameLayout parent) {
        try {
            Context ctx = parent.getContext();

            TextView button = new TextView(ctx);
            button.setText(label(ctx));
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setGravity(Gravity.CENTER);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10));
            button.setMinHeight(dp(ctx, 40));
            style(ctx, button);
            button.setOnClickListener(v -> {
                Object rv = mRecentsView;
                if (rv == null) {
                    XposedBridge.log(TAG + "no RecentsView captured yet");
                    return;
                }
                try {
                    mDismissAllTasks.invoke(rv, v);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(ctx, 16);
            lp.leftMargin = dp(ctx, 16);
            lp.gravity = gravity(ctx) | Gravity.CENTER_VERTICAL;
            button.setLayoutParams(lp);

            // The root wraps its content, so a child would otherwise fall outside the
            // touchable area. This is what makes the button clickable in the floating case.
            parent.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;

            // Deliberately not attached yet. Which parent is correct depends on the panel,
            // and that cannot be known until the row has been laid out. Attaching here
            // would briefly show the floating variant on the cover panel before the
            // re-parent caught up.
            button.setVisibility(View.GONE);
            mButton = button;

            // Per-frame, not per-layout. Overview fades in and out by animating alpha,
            // which invalidates and redraws but never triggers a layout pass -- so an
            // OnGlobalLayoutListener simply does not run during those transitions, and
            // the button keeps whatever visibility it last had. onPreDraw fires on every
            // drawn frame, so the gate is re-evaluated while alphas animate.
            final FrameLayout host = parent;
            host.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            syncWithStock(host);
                            return true;   // never block the frame
                        }
                    });

        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /**
     * Show only when Overview is up AND it actually holds tasks, so the button does not
     * linger over an empty "No recent items" screen. Driven from both the setVisibility
     * hook and every layout pass, the latter catching the moment the last task is
     * dismissed.
     */
    /**
     * Measured: during the stray flash every readable value -- scale, contentAlpha,
     * mOverviewStateEnabled, the whole alpha chain, even the stock button's own alpha
     * and position -- is identical to a genuine Overview session. The launcher really
     * is in the same state; it simply leaves it again ~190ms later. No instantaneous
     * condition can separate the two, so the floating variant waits instead: it reveals
     * only once the state has held for longer than a flash lasts, then fades in.
     *
     * The row variant is left alone. It is already correct, because sitting inside
     * action_buttons means the row's own alpha does this work.
     */
    private void updateVisibility() {
        TextView mine = mButton;
        if (mine == null || mine.getParent() == null) return;
        try {
            boolean want = mSettled && overviewSettled();

            mWantVisible = want;

            if (mine.getParent() instanceof LinearLayout) {
                mine.setVisibility(want ? View.VISIBLE : View.GONE);
                return;
            }

            if (!want) {
                mine.removeCallbacks(mReveal);
                mine.animate().cancel();
                mRevealed = false;
                mine.setVisibility(View.GONE);
                return;
            }

            if (mRevealed) {
                mine.setAlpha(mContentAlpha);      // keep tracking the overview fade
            } else if (!mRevealPending) {
                mRevealPending = true;
                mine.postDelayed(mReveal, revealDelay(mine.getContext()));
            }
        } catch (Throwable t) {
            mine.setVisibility(View.GONE);
        }
    }

    private final Runnable mReveal = new Runnable() {
        @Override
        public void run() {
            mRevealPending = false;
            TextView mine = mButton;
            if (mine == null || !mWantVisible || !overviewSettled()) return;
            mRevealed = true;
            mine.setAlpha(0f);
            mine.setVisibility(View.VISIBLE);
            mine.animate().alpha(mContentAlpha).setDuration(FADE_MS).start();
        }
    };

    /**
     * Overview is up, finished animating, and has something to clear.
     *
     * The decisive part is the alpha walk: during a quick-switch swipe or while Overview
     * animates in or out, RecentsView and its ancestors are partially faded. Requiring
     * them to be fully opaque means transient states never qualify, which no combination
     * of boolean flags reliably caught.
     */
    private boolean overviewSettled() {
        Object rv = mRecentsView;
        if (!(rv instanceof View)) return false;
        View v = (View) rv;

        // Nothing shows while the user is still dragging.
        if (mGestureActive) return false;

        // Overview is on its way out well before the scale ramp leaves 1.0.
        if (mContentAlpha < 0.01f) return false;

        // Scale is the discriminator, measured on device: whenever Overview is not the
        // active state -- including a *held* quick-switch gesture, where nothing is
        // animating -- RecentsView is parked at ~1.4x behind the workspace, VISIBLE and
        // fully opaque, with mOverviewStateEnabled still true. Alpha, visibility and that
        // flag are all identical to real Overview; only the scale differs, settling to
        // 1.0 when Overview is genuinely up. This also covers the enter/exit animations,
        // since the scale is mid-travel throughout.
        if (Math.abs(v.getScaleX() - 1f) > 0.02f) return false;

        return fullyOpaque(v) && taskCount() > 0;
    }

    /** VISIBLE with alpha ~1 all the way up the tree. */
    private static boolean fullyOpaque(View v) {
        for (View cur = v; cur != null; ) {
            if (cur.getVisibility() != View.VISIBLE) return false;
            if (cur.getAlpha() < 0.95f) return false;
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    private static long revealDelay(Context ctx) {
        try {
            return Math.max(0, Settings.System.getInt(
                    ctx.getContentResolver(), KEY_REVEAL_DELAY, DEFAULT_REVEAL_DELAY_MS));
        } catch (Throwable ignored) {
            return DEFAULT_REVEAL_DELAY_MS;
        }
    }

    private int taskCount() {
        Object rv = mRecentsView;
        if (rv == null || mTaskViewCount == null) return 0;
        try {
            Object n = mTaskViewCount.invoke(rv);
            return n instanceof Integer ? (Integer) n : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The Screenshot/Select row, on the panels that actually use it.
     *
     * Chosen from the screen configuration, never from the row's live alpha. Alpha dips
     * below any threshold while Overview fades in and out, which made the choice flip
     * mid-transition: the button re-parented to the floating position and flashed there
     * on a panel that should never show that variant. Which layout the launcher uses is
     * a property of the display -- updateForIsTablet() keys off DeviceProfile.isTablet --
     * so it is stable for the whole time a panel is active, and a fold triggers a
     * configuration change that re-evaluates it.
     *
     * Staying inside the row is also why the fade now looks right: the button inherits
     * the row's alpha and animates in and out exactly with Screenshot and Select.
     */
    private static ViewGroup actionsRow(FrameLayout host) {
        if (host.getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            return null;   // tablet-class: actions move into the task menu
        }
        int id = host.getResources().getIdentifier(
                "action_buttons", "id", host.getContext().getPackageName());
        if (id == 0) return null;
        View row = host.findViewById(id);
        return row instanceof ViewGroup ? (ViewGroup) row : null;
    }

    /** Locate the launcher's own ClearAllButton -- a page inside RecentsView. */
    private TextView stockButton(Context ctx) {
        Object rv = mRecentsView;
        if (!(rv instanceof View)) return null;
        int id = ctx.getResources().getIdentifier("clear_all", "id", ctx.getPackageName());
        if (id == 0) return null;
        View v = ((View) rv).findViewById(id);
        return v instanceof TextView ? (TextView) v : null;
    }

    private void syncWithStock(FrameLayout host) {
        try {
            final TextView mine = mButton;
            if (mine == null) return;

            if (!overviewSettled()) {
                // Mid-transition, quick-switch, or not in Overview at all. Stay hidden and
                // leave the parent alone -- re-parenting while alphas animate is what made
                // the button flash in the wrong place.
                mine.setVisibility(View.GONE);
                return;
            }

            final ViewGroup row = actionsRow(host);
            final ViewGroup wanted = row != null ? row : host;

            // Narrow panels put it in the Screenshot/Select row, so the LinearLayout
            // handles spacing and re-centres the whole group; wide panels float it beside
            // the task carousel. Re-parenting is deferred, layout is already in progress.
            if (mine.getParent() != wanted) {
                // Clear these now, not in the posted runnable: a visibility update can
                // land in between, and a stale "settled" from the other panel would show
                // the wrong variant. Folding is exactly that case.
                mSettled = false;
                mine.setVisibility(View.GONE);
                host.post(new Runnable() {
                    @Override public void run() { reparent(mine, wanted, host); }
                });
                return;
            }

            // Match whichever control it actually sits next to.
            View source = row != null ? firstButton(row) : stockButton(host.getContext());
            if (source != null && source.getWidth() > 0 && source != mLookSource) {
                copyLook((TextView) source, mine, row == null);
                mLookSource = source;
                mSettled = true;
            }

            if (row == null) {
                TextView stock = stockButton(host.getContext());
                if (stock != null && stock.getWidth() > 0) alignVertically(stock, mine);
            }
            updateVisibility();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void reparent(TextView mine, ViewGroup wanted, FrameLayout host) {
        try {
            ViewGroup current = (ViewGroup) mine.getParent();
            if (current == wanted) return;
            if (current != null) current.removeView(mine);

            if (wanted instanceof LinearLayout) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(nativeGap(wanted));
                wanted.addView(mine, lp);
            } else {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = gravity(host.getContext()) | Gravity.CENTER_VERTICAL;
                lp.leftMargin = dp(host.getContext(), 16);
                lp.rightMargin = dp(host.getContext(), 16);
                wanted.addView(mine, lp);
            }
            mLookSource = null;   // re-copy against the new neighbours
            mSettled = false;
            mine.removeCallbacks(mReveal);
            mRevealed = false;
            mRevealPending = false;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** First laid-out button already in the row, used as the styling reference. */
    private static View firstButton(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c != null && c.getVisibility() == View.VISIBLE
                    && c instanceof TextView && c.getWidth() > 0) {
                return c;
            }
        }
        return null;
    }

    /** Reuse the row's own inter-button spacing rather than inventing one. */
    private static int nativeGap(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = row.getChildAt(i).getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                int m = ((ViewGroup.MarginLayoutParams) lp).getMarginStart();
                if (m > 0) return m;
            }
        }
        return dp(row.getContext(), 8);
    }

    /** Take the stock button's own drawable and metrics rather than approximating them. */
    private static void copyLook(TextView stock, TextView mine, boolean pinSize) {
        mine.setTextColor(stock.getTextColors());
        mine.setTextSize(TypedValue.COMPLEX_UNIT_PX, stock.getTextSize());
        mine.setTypeface(stock.getTypeface());
        mine.setAllCaps(stock.isAllCaps());
        mine.setGravity(stock.getGravity());
        mine.setPadding(stock.getPaddingLeft(), stock.getPaddingTop(),
                stock.getPaddingRight(), stock.getPaddingBottom());
        mine.setMinWidth(stock.getMinWidth());
        mine.setMinHeight(stock.getMinHeight());
        mine.setElevation(stock.getElevation());
        mine.setStateListAnimator(null);

        Drawable bg = stock.getBackground();
        if (bg != null && bg.getConstantState() != null) {
            mine.setBackground(bg.getConstantState().newDrawable(stock.getResources()));
        }

        ViewGroup.LayoutParams lp = mine.getLayoutParams();
        if (pinSize) {
            // Floating beside the carousel: reproduce the stock pill exactly.
            lp.width = stock.getWidth();
            lp.height = stock.getHeight();
            mine.setCompoundDrawablesRelative(null, null, null, null);
        } else {
            // In the row: same height as its neighbours plus an icon, so it reads as one
            // of them. Width still wraps, since the label differs.
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = stock.getHeight();
            addCrossIcon(stock, mine);
        }
        mine.setLayoutParams(lp);
    }

    /** Put our centre on the anchor's centre, in screen coordinates. */
    private static void alignVertically(View stock, TextView mine) {
        ViewGroup host = (ViewGroup) mine.getParent();
        if (host == null) return;

        int[] hostAt = new int[2], stockAt = new int[2];
        host.getLocationOnScreen(hostAt);
        stock.getLocationOnScreen(stockAt);

        int height = mine.getHeight() > 0 ? mine.getHeight() : stock.getHeight();
        int top = (stockAt[1] + stock.getHeight() / 2) - hostAt[1] - height / 2;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mine.getLayoutParams();
        int gravity = (lp.gravity & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.TOP;
        if (lp.gravity != gravity || lp.topMargin != top) {
            lp.gravity = gravity;
            lp.topMargin = top;
            mine.setLayoutParams(lp);
        }
    }

    /** A leading glyph sized and spaced like the neighbouring buttons' icons. */
    private static void addCrossIcon(TextView sibling, TextView mine) {
        int size = 0, pad = sibling.getCompoundDrawablePadding();
        for (Drawable d : sibling.getCompoundDrawablesRelative()) {
            if (d != null && d.getIntrinsicWidth() > 0) {
                size = d.getIntrinsicWidth();
                break;
            }
        }
        if (size <= 0) size = dp(sibling.getContext(), 20);
        if (pad <= 0) pad = dp(sibling.getContext(), 8);

        Drawable icon = new CrossDrawable(size, mine.getCurrentTextColor(),
                Math.max(2f, size * 0.09f));
        mine.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        mine.setCompoundDrawablePadding(pad);
    }

    /** A plain X, drawn rather than shipped, so it inherits the label's colour. */
    private static final class CrossDrawable extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mSize;

        CrossDrawable(int size, int color, float stroke) {
            mSize = size;
            mPaint.setColor(color);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(stroke);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public int getIntrinsicWidth() { return mSize; }
        @Override public int getIntrinsicHeight() { return mSize; }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float in = b.width() * 0.27f;
            canvas.drawLine(b.left + in, b.top + in, b.right - in, b.bottom - in, mPaint);
            canvas.drawLine(b.right - in, b.top + in, b.left + in, b.bottom - in, mPaint);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { mPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static CharSequence label(Context ctx) {
        int id = ctx.getResources().getIdentifier(
                "recents_clear_all", "string", ctx.getPackageName());
        return id != 0 ? ctx.getString(id) : "Clear all";
    }

    /**
     * An outlined pill: transparent fill, hairline border, light label.
     *
     * textColorPrimary, not textColorPrimaryInverse -- in dark mode the inverse is the
     * dark one, which is what made the first attempt unreadable against Overview.
     */
    private static void style(Context ctx, TextView button) {
        int fg = attrColor(ctx, android.R.attr.textColorPrimary, Color.WHITE);
        button.setTextColor(fg);

        GradientDrawable outline = new GradientDrawable();
        outline.setShape(GradientDrawable.RECTANGLE);
        outline.setCornerRadius(dp(ctx, 24));
        outline.setColor(Color.TRANSPARENT);
        outline.setStroke(Math.max(1, dp(ctx, 1)), withAlpha(fg, 0.55f));

        int highlight = attrColor(ctx, android.R.attr.colorControlHighlight, 0x33FFFFFF);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(highlight), outline, null));
        button.setClickable(true);
        button.setFocusable(true);
    }

    private static int withAlpha(int color, float factor) {
        return Color.argb(Math.round(255 * factor),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int attrColor(Context ctx, int attr, int fallback) {
        try (TypedArray a = ctx.obtainStyledAttributes(new int[]{attr})) {
            return a.getColor(0, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int gravity(Context ctx) {
        int v = END;
        try {
            v = Settings.System.getInt(ctx.getContentResolver(), KEY_GRAVITY, END);
        } catch (Throwable ignored) {
        }
        if (v == START) return Gravity.START;
        if (v == CENTER) return Gravity.CENTER_HORIZONTAL;
        return Gravity.END;
    }

    private static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}

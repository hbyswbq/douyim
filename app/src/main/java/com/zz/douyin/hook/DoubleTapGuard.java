package com.zz.douyin.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Consume the recognized double tap, without turning it into two single taps. */
final class DoubleTapGuard {
    private static final Set<Method> HOOKED = new HashSet<>();
    private static int doubleTapMode = 0; // 0=点赞, 1=打开评论
    private static Activity currentActivity;

    private static final String VIEW_HOLDER_ROOT =
            "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView";

    private DoubleTapGuard() {}

    static void configure(SharedPreferences preferences) {
        doubleTapMode = com.zz.douyin.FilterPreferences.readDoubleTapMode(preferences);
    }

    static void setCurrentActivity(Activity activity) {
        currentActivity = activity;
    }

    static void installFeedInterceptor(DouyinModule module, ClassLoader loader) throws Exception {
        Class<?> group = Class.forName(
                "com.ss.android.ugc.aweme.feed.plato.core.FeedComponentGroup", false, loader);
        Method intercept = group.getDeclaredMethod(
                "interceptDoubleClick", MotionEvent.class, MotionEvent.class);
        if (intercept.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("unexpected feed double-click interceptor contract");
        }
        module.hook(intercept)
                .setId("douyin-block-feed-double-click")
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (ImmersiveUi.shouldBlockDoubleTap()) {
                        Log.i(DouyinModule.TAG, "blocked feed double tap before component dispatch");
                        return true;
                    }
                    // 双击打开评论模式：拦截默认点赞，转而打开评论区
                    if (doubleTapMode == 1) {
                        Log.i(DouyinModule.TAG, "double tap -> open comment");
                        openCommentPanel();
                        return true;
                    }
                    return chain.proceed();
                });
    }

    static void installPlatformListeners(DouyinModule module) throws Exception {
        for (Constructor<?> constructor : GestureDetector.class.getDeclaredConstructors()) {
            module.hook(constructor)
                    .setId("douyin-double-tap-constructor-" + constructor.toGenericString())
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        for (Object argument : chain.getArgs()) {
                            if (argument instanceof GestureDetector.OnDoubleTapListener) {
                                hookListener(module, argument.getClass());
                            }
                        }
                        return result;
                    });
        }
        module.hook(GestureDetector.class.getDeclaredMethod(
                        "setOnDoubleTapListener", GestureDetector.OnDoubleTapListener.class))
                .setId("douyin-double-tap-listener-setter")
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object listener = chain.getArgs().get(0);
                    if (listener != null) hookListener(module, listener.getClass());
                    return result;
                });
    }

    private static synchronized void hookListener(DouyinModule module, Class<?> type)
            throws NoSuchMethodException {
        for (String name : new String[]{"onDoubleTap", "onDoubleTapEvent"}) {
            Method method = type.getMethod(name, MotionEvent.class);
            if (HOOKED.contains(method)) continue;
            module.hook(method)
                    .setId("douyin-block-" + method.toGenericString())
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (ImmersiveUi.shouldBlockDoubleTap()) {
                            if (name.equals("onDoubleTap")) {
                                Log.i(DouyinModule.TAG, "blocked native double tap: "
                                        + chain.getThisObject().getClass().getName());
                            }
                            return true;
                        }
                        // 双击打开评论模式
                        if (doubleTapMode == 1 && name.equals("onDoubleTap")) {
                            Log.i(DouyinModule.TAG, "native double tap -> open comment");
                            openCommentPanel();
                            return true;
                        }
                        return chain.proceed();
                    });
            HOOKED.add(method);
            module.log(Log.DEBUG, DouyinModule.TAG, "double tap listener: " + method);
        }
    }

    /**
     * 在当前视频卡片（VideoViewHolderRootView）内找到评论按钮并打开评论区。
     * 移植自 FreedomPlus：限定搜索范围到当前卡片，避免命中 ViewPager 缓存的
     * 相邻卡片；优先直接调用 OnClickListener，绕过沉浸模式下的触摸拦截与
     * performClick 触发的 UI 状态切换。
     */
    private static void openCommentPanel() {
        try {
            Activity activity = currentActivity;
            if (activity == null) {
                return;
            }
            View root = activity.getWindow().getDecorView();

            // 1. 定位当前可见的视频卡片
            View card = findCurrentVideoCard(root);
            if (card == null) {
                Log.w(DouyinModule.TAG, "current video card not found");
                return;
            }

            // 2. 在卡片内找到评论按钮
            View commentButton = findCommentButton(card);
            if (commentButton == null) {
                Log.w(DouyinModule.TAG, "comment button not found in current card");
                return;
            }

            // 3. 优先直接调用 OnClickListener（纯 Java 调用，不经触摸分发）
            // 同时抑制 ImmersiveUi 的单击暂停，避免双击被拆成单击而退出沉浸
            ImmersiveUi.suppressSingleTap(700L);
            View.OnClickListener listener = getOnClickListener(commentButton);
            if (listener != null) {
                listener.onClick(commentButton);
                Log.i(DouyinModule.TAG, "invoked comment OnClickListener: "
                        + commentButton.getClass().getName());
            } else {
                // 4. 回退：向该按钮注入真实手势
                simulateTap(commentButton);
                Log.i(DouyinModule.TAG, "simulated tap on comment button: "
                        + commentButton.getClass().getName());
            }
        } catch (Throwable t) {
            Log.e(DouyinModule.TAG, "open comment panel failed", t);
        }
    }

    /** 找到占满屏幕的当前视频卡片（ViewPager 中相邻卡片在屏幕外）。 */
    private static View findCurrentVideoCard(View root) {
        List<View> cards = new ArrayList<>();
        collectByClassName(root, VIEW_HOLDER_ROOT, cards);
        View best = null;
        Rect visible = new Rect();
        for (View card : cards) {
            if (!card.isAttachedToWindow() || !card.isShown()) {
                continue;
            }
            if (card.getGlobalVisibleRect(visible)
                    && visible.width() >= root.getWidth() * 0.8f
                    && visible.height() >= root.getHeight() * 0.8f) {
                best = card;
            }
        }
        return best;
    }

    private static void collectByClassName(View view, String className, List<View> out) {
        if (className.equals(view.getClass().getName())) {
            out.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectByClassName(group.getChildAt(i), className, out);
            }
        }
    }

    /**
     * 在卡片内查找评论按钮。匹配 contentDescription 含"评论"，
     * 且是可点击 View 或描述含"按钮"（对应 TalkBack 的"评论…，按钮"）。
     */
    private static View findCommentButton(View view) {
        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            String text = desc.toString();
            if (text.contains("评论") && (text.contains("按钮") || view.isClickable())) {
                return view;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findCommentButton(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 向 View 中心注入 DOWN+UP 触摸事件，模拟真实点击。 */
    private static void simulateTap(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = location[0] + view.getWidth() / 2f;
        float y = location[1] + view.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0);
        try {
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    /** 通过反射获取 View 的 OnClickListener。 */
    private static View.OnClickListener getOnClickListener(View view) {
        try {
            java.lang.reflect.Field field = View.class.getDeclaredField("mListenerInfo");
            field.setAccessible(true);
            Object listenerInfo = field.get(view);
            if (listenerInfo == null) {
                return null;
            }
            java.lang.reflect.Field onClickField =
                    listenerInfo.getClass().getDeclaredField("mOnClickListener");
            onClickField.setAccessible(true);
            Object listener = onClickField.get(listenerInfo);
            return listener instanceof View.OnClickListener
                    ? (View.OnClickListener) listener
                    : null;
        } catch (Throwable t) {
            return null;
        }
    }
}

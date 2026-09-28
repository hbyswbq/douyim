package com.zz.douyin.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Consume the recognized double tap, without turning it into two single taps. */
final class DoubleTapGuard {
    private static final Set<Method> HOOKED = new HashSet<>();
    private static int doubleTapMode = 0; // 0=点赞, 1=打开评论
    private static Activity currentActivity;

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
     * 遍历 View 树找到评论按钮并模拟点击。
     * 移植自 FreedomPlus 的 onClickView，通过 contentDescription 匹配"评论"。
     */
    private static void openCommentPanel() {
        try {
            Activity activity = currentActivity;
            if (activity == null) {
                return;
            }
            View root = activity.getWindow().getDecorView();
            View commentButton = findViewByContentDescription(root, "评论");
            if (commentButton != null && commentButton.isShown()) {
                commentButton.performClick();
                Log.i(DouyinModule.TAG, "clicked comment button: " + commentButton.getClass().getName());
            } else {
                Log.w(DouyinModule.TAG, "comment button not found");
            }
        } catch (Throwable t) {
            Log.e(DouyinModule.TAG, "open comment panel failed", t);
        }
    }

    private static View findViewByContentDescription(View root, String keyword) {
        if (root == null) {
            return null;
        }
        CharSequence desc = root.getContentDescription();
        if (desc != null && desc.toString().contains(keyword)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByContentDescription(group.getChildAt(i), keyword);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

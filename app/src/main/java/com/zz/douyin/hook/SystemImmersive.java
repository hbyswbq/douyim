package com.zz.douyin.hook;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 全屏沉浸：隐藏状态栏/导航栏，内容延伸到刘海区域。
 * 移植自 FreedomPlus ImmersiveHelper。
 */
final class SystemImmersive {
    private static boolean enabled = false;
    private static boolean hideStatusBar = false;
    private static boolean hideNavBar = false;

    private SystemImmersive() {}

    static void configure(boolean enabled, boolean hideStatusBar, boolean hideNavBar) {
        SystemImmersive.enabled = enabled;
        SystemImmersive.hideStatusBar = hideStatusBar;
        SystemImmersive.hideNavBar = hideNavBar;
    }

    static void install(DouyinModule module) throws Exception {
        Method onWindowFocusChanged = Activity.class.getDeclaredMethod(
                "onWindowFocusChanged", boolean.class
        );
        module.hook(onWindowFocusChanged)
                .setId("douyin-system-immersive-focus")
                .setExceptionMode(XposedModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (enabled) {
                        applyImmersive((Activity) chain.getThisObject());
                    }
                    return result;
                });

        // onResume 时也应用一次，确保切换页面后生效
        Method onResume = Activity.class.getDeclaredMethod("onResume");
        module.hook(onResume)
                .setId("douyin-system-immersive-resume")
                .setExceptionMode(XposedModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (enabled) {
                        applyImmersive((Activity) chain.getThisObject());
                    }
                    return result;
                });
    }

    private static void applyImmersive(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }

        try {
            // 让内容延伸到系统栏区域
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            }

            // 隐藏/显示状态栏和导航栏
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                    if (hideStatusBar) {
                        controller.hide(android.view.WindowInsets.Type.statusBars());
                    } else {
                        controller.show(android.view.WindowInsets.Type.statusBars());
                    }
                    if (hideNavBar) {
                        controller.hide(android.view.WindowInsets.Type.navigationBars());
                    } else {
                        controller.show(android.view.WindowInsets.Type.navigationBars());
                    }
                }
            } else {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (hideStatusBar) {
                    flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
                }
                if (hideNavBar) {
                    flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                }
                window.getDecorView().setSystemUiVisibility(flags);
            }

            // 系统栏透明
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);

            // 刘海屏适配：允许内容延伸到刘海区域
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
        } catch (Throwable t) {
            // 沉浸式设置失败不影响正常使用
        }
    }
}

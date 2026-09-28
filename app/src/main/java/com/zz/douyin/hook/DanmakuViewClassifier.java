package com.zz.douyin.hook;

final class DanmakuViewClassifier {
    // Douyin 39.7.0+ uses the Compose-based DDanmakuComposeView.
    private static final String ULTRA_COMPOSE_RENDERER =
            "com.bytedance.common.ultra.danmaku.ddanmaku.DDanmakuComposeView";
    // Douyin 31.7.2 uses the older ultra DanmakuView and meteor SurfaceView renderers.
    private static final String[] LEGACY_RENDERERS = {
            "com.bytedance.common.ultra.danmaku.view.DanmakuView",
            "com.bytedance.common.ultra.danmaku.view.DanmakuTextureView",
            "com.bytedance.common.meteor.DanmakuSurfaceView",
            "com.bytedance.common.meteor.DanmakuSkitySurfaceView",
    };

    private DanmakuViewClassifier() {
    }

    static boolean isRenderView(String className) {
        if (ULTRA_COMPOSE_RENDERER.equals(className)) {
            return true;
        }
        for (String renderer : LEGACY_RENDERERS) {
            if (renderer.equals(className)) {
                return true;
            }
        }
        return false;
    }
}

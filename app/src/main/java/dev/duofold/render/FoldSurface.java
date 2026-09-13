package dev.duofold.render;

import android.hardware.HardwareBuffer;
import android.view.Display;
import android.view.SurfaceControl;
import dev.duofold.motion.FoldModel;

/** Common surface contract implemented by the OpenGL (FoldView) and Vulkan (FoldViewVk) renderers. */
public interface FoldSurface {
    interface Listener {
        void onReady(int width,int height);
        void onFirstFrame();
        void onError(String message);
    }
    void setFold(float forwardRadians,float sideRadians,float intensity);
    void setBlurScale(float scale);
    void setStretch(float value);
    void queueBuffer(HardwareBuffer buffer);
    FoldModel.State inputState();
    void releaseResources();
    void onPause();
    void saveDebugFrame();
    // View API surfaced for FoldService
    int getWidth();
    int getHeight();
    Display getDisplay();
    SurfaceControl getSurfaceControl();
    void setAlpha(float alpha);
}

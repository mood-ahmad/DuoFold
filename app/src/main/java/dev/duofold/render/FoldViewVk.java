package dev.duofold.render;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import dev.duofold.motion.FoldModel;

/** Vulkan-backed fold surface: imports captured HardwareBuffers natively and renders via a swapchain. */
public final class FoldViewVk extends SurfaceView implements SurfaceHolder.Callback, FoldSurface {
    static { System.loadLibrary("duofoldvk"); }
    private final Listener listener;
    private volatile float blurScale=1, stretch=1;
    private volatile FoldModel.State state=FoldModel.atAxes(0,0,1,400,800);
    private volatile FoldModel.State drawnState=FoldModel.atAxes(0,0,1,400,800);
    private volatile long nativeHandle;
    private int width,height;
    private volatile boolean stopped;
    private volatile boolean firstFrameReported;

    public FoldViewVk(Context context,Listener listener) {
        super(context); this.listener=listener;
        getHolder().addCallback(this);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h) {
        if(stopped) return;
        width=w; height=h;
        int captureWidth=Math.min(w,1080), captureHeight=Math.round(h*(captureWidth/(float)w));
        if(nativeHandle==0) nativeHandle=nativeInit(holder.getSurface(),w,h);
        else nativeSurfaceChanged(nativeHandle,w,h);
        if(nativeHandle==0) { post(()->listener.onError("Vulkan 初始化失败")); return; }
        post(()-> { if(!stopped) listener.onReady(captureWidth,captureHeight); });
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseNative(); }

    private void releaseNative() {
        long h=nativeHandle; nativeHandle=0;
        if(h!=0) nativeRelease(h);
    }

    @Override public void setFold(float forwardRadians,float sideRadians,float intensity) {
        float density=getResources().getDisplayMetrics().density;
        float widthPoints=Math.max(1,getWidth())/density,heightPoints=Math.max(1,getHeight())/density;
        state=FoldModel.atAxes(forwardRadians,sideRadians,intensity,widthPoints,heightPoints);
        drawnState=state;
        pushParams();
    }
    @Override public void setBlurScale(float scale) { blurScale=scale; pushParams(); }
    @Override public void setStretch(float value) { stretch=value; pushParams(); }
    private void pushParams() {
        long h=nativeHandle; if(h==0) return;
        FoldModel.State s=state;
        nativeSetParams(h,getWidth(),getHeight(),s.angleX,s.angleY,s.widthPoints,s.heightPoints,
                s.intensity,blurScale,stretch);
    }
    @Override public void queueBuffer(HardwareBuffer buffer) {
        long h=nativeHandle;
        if(h==0) { if(buffer!=null) buffer.close(); return; }
        nativeSubmit(h,buffer);
    }
    @Override public FoldModel.State inputState() { return drawnState; }
    @Override public void releaseResources() { stopped=true; releaseNative(); }
    @Override public void onPause() {}
    @Override public void saveDebugFrame() {}

    void nativeOnFirstFrame() {
        post(()-> { if(!firstFrameReported && !stopped) { firstFrameReported=true; listener.onFirstFrame(); } });
    }

    private native long nativeInit(Surface surface,int w,int h);
    private native void nativeSubmit(long handle,HardwareBuffer buffer);
    private native void nativeSetParams(long handle,float resX,float resY,float angleX,float angleY,
            float sizeX,float sizeY,float intensity,float blurScale,float stretch);
    private native void nativeSurfaceChanged(long handle,int w,int h);
    private native void nativeRelease(long handle);
}

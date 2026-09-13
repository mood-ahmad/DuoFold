package dev.duofold.bridge;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Runs only inside the Shizuku shell UserService, never inside the app process. */
public final class CaptureBridge extends IFoldBridge.Stub {
    private static final int NONE=0,IWM_CAPTURE=1,SURFACE_CAPTURE=2;
    private final HandlerThread thread=new HandlerThread("DuoCapture");
    private final Handler handler;
    private volatile boolean running;
    private volatile long heartbeat;
    private volatile long frames;
    private volatile String error="";
    private SurfaceControl exclude;
    private Object captureArgs;
    private Object windowManager;
    private Object inputManager;
    private IBinder displayToken;
    private Method capture,createCaptureListener,getCaptureBuffer,inject;
    private MotionEvent lastTouch;
    private ICaptureListener listener;
    private int width,height,interval,backend;

    public CaptureBridge() {thread.start();handler=new Handler(thread.getLooper());}
    public CaptureBridge(Context context) {this();}

    @Override public Bundle probe() {
        Bundle result=new Bundle();result.putInt("uid",Process.myUid());
        try {
            initInput();
            Throwable first=null;
            try {initIwmCapture();} catch(Throwable e) {first=e;backend=NONE;}
            if(backend==NONE)try {initSurfaceCapture();} catch(Throwable e) {if(first==null)first=e;backend=NONE;}
            if(backend==NONE && first!=null) throw first;
            result.putBoolean("captureApi",true);result.putBoolean("inputApi",inject!=null);
            result.putString("backend",backendName());
            result.putString("message","Shell APIs ready: "+backendName());
        } catch(Throwable e) {result.putString("message",describe(e));}
        return result;
    }

    private void initInput() throws Exception {
        try {
            Class<?> im=Class.forName("android.hardware.input.InputManagerGlobal");
            inputManager=im.getMethod("getInstance").invoke(null);
            inject=im.getMethod("injectInputEvent",InputEvent.class,int.class);
        } catch(ClassNotFoundException|NoSuchMethodException e) {
            Class<?> im=Class.forName("android.hardware.input.InputManager");
            inputManager=im.getMethod("getInstance").invoke(null);
            inject=im.getMethod("injectInputEvent",InputEvent.class,int.class);
        }
    }

    /** Android 17 moved the capture internals from ScreenCapture to ScreenCaptureInternal. */
    private static Class<?> captureHolder() throws ClassNotFoundException {
        try { return Class.forName("android.window.ScreenCaptureInternal"); }
        catch (ClassNotFoundException e) { return Class.forName("android.window.ScreenCapture"); }
    }

    /** Android 17 removed SurfaceControl.getInternalDisplayToken(); the IWindowManager path uses a display id instead. */
    private IBinder internalDisplayToken() throws Exception {
        try {
            return (IBinder)Class.forName("android.view.SurfaceControl")
                    .getMethod("getInternalDisplayToken").invoke(null);
        } catch(NoSuchMethodException e) {
            throw new UnsupportedOperationException(
                    "SurfaceControl display-token API removed on Android 17; use IWindowManager path");
        }
    }

    private void initIwmCapture() throws Exception {
        Class<?> holder=captureHolder();
        Class<?> args=Class.forName(holder.getName()+"$CaptureArgs");
        Class<?> listenerType=Class.forName(holder.getName()+"$ScreenCaptureListener");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class)
                .invoke(null,"window");
        windowManager=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class)
                .invoke(null,binder);
        capture=Class.forName("android.view.IWindowManager").getMethod("captureDisplay",int.class,args,listenerType);
        createCaptureListener=holder.getMethod("createSyncCaptureListener");
        getCaptureBuffer=Class.forName(holder.getName()+"$SynchronousScreenCaptureListener")
                .getMethod("getBuffer");
        backend=IWM_CAPTURE;
    }

    private void initSurfaceCapture() throws Exception {
        displayToken=internalDisplayToken();
        Class<?> surfaceControl=Class.forName("android.view.SurfaceControl");
        Class<?> args=Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        capture=surfaceControl.getMethod("captureDisplay",args);
        backend=SURFACE_CAPTURE;
    }

    @Override public void start(Bundle options,ICaptureListener callback) {
        heartbeat=SystemClock.uptimeMillis();
        handler.post(()-> {
            release();
            try {
                Bundle probe=probe();
                if(!probe.getBoolean("captureApi")||!probe.getBoolean("inputApi"))
                    throw new IllegalStateException(probe.getString("message"));
                listener=callback;
                exclude=(SurfaceControl)options.getParcelable("exclude");
                width=options.getInt("width");height=options.getInt("height");
                interval=1000/Math.max(30,Math.min(180,options.getInt("fps",60)));
                if(exclude==null||!exclude.isValid())
                    throw new IllegalStateException("Effect layer unavailable; refusing recursive capture.");
                markExcluded(exclude);
                buildCaptureArgs(options);
                frames=0;error="";running=true;
                // Let SurfaceFlinger apply the exclusion before the first capture, otherwise the
                // opening frames include the overlay itself and flash a recursive image.
                handler.postDelayed(()-> { if(running) handler.post(frame); },150);
            } catch(Throwable e) {fail(e);}
        });
    }

    private void markExcluded(SurfaceControl layer) throws Exception {
        try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) {
            SurfaceControl.Transaction.class.getMethod("setSkipScreenshot",SurfaceControl.class,boolean.class)
                    .invoke(transaction,layer,true);
            transaction.apply();
        }
    }

    private void buildCaptureArgs(Bundle options) throws Exception {
        captureArgs=null;
        if(backend==IWM_CAPTURE) {
            Class<?> builderClass=Class.forName(captureHolder().getName()+"$CaptureArgs$Builder");
            Object builder=builderClass.getConstructor().newInstance();
            float sx=width/(float)Math.max(1,options.getInt("displayWidth",width));
            float sy=height/(float)Math.max(1,options.getInt("displayHeight",height));
            builderClass.getMethod("setFrameScale",float.class,float.class).invoke(builder,sx,sy);
            builderClass.getMethod("setExcludeLayers",SurfaceControl[].class)
                    .invoke(builder,(Object)new SurfaceControl[]{exclude});
            try { builderClass.getMethod("setCaptureSecureLayers",boolean.class).invoke(builder,false); }
            catch(NoSuchMethodException ignored) {} // Android 17: policy defaults already exclude secure layers
            try { builderClass.getMethod("setAllowProtected",boolean.class).invoke(builder,false); }
            catch(NoSuchMethodException ignored) {} // Android 17: policy defaults already exclude protected layers
            try { builderClass.getMethod("setIncludeSystemOverlays",boolean.class).invoke(builder,true); }
            catch(NoSuchMethodException ignored) {} // Android 17 defaults to false: status bar / dock would vanish
            captureArgs=builderClass.getMethod("build").invoke(builder);
        } else if(backend==SURFACE_CAPTURE) {
            Class<?> builderClass=Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
            Object builder=builderClass.getConstructor(IBinder.class).newInstance(displayToken);
            builderClass.getMethod("setSize",int.class,int.class).invoke(builder,width,height);
            builderClass.getMethod("setCaptureSecureLayers",boolean.class).invoke(builder,false);
            builderClass.getMethod("setAllowProtected",boolean.class).invoke(builder,false);
            try { builderClass.getMethod("setIncludeSystemOverlays",boolean.class).invoke(builder,true); }
            catch(NoSuchMethodException ignored) {}
            captureArgs=builderClass.getMethod("build").invoke(builder);
        }
    }

    private final Runnable frame=new Runnable() {
        @Override public void run() {
            if(!running)return;
            if(SystemClock.uptimeMillis()-heartbeat>3000) {
                fail(new IllegalStateException("Client heartbeat expired."));return;
            }
            long started=SystemClock.uptimeMillis();HardwareBuffer buffer=null;boolean sent=false;
            try {
                if(exclude==null||!exclude.isValid())throw new IllegalStateException("Excluded layer lost.");
                Object screenshot;
                if(backend==IWM_CAPTURE) {
                    Object sync=createCaptureListener.invoke(null);
                    capture.invoke(windowManager,0,captureArgs,sync);
                    screenshot=getCaptureBuffer.invoke(sync);
                } else screenshot=capture.invoke(null,captureArgs);
                if(screenshot==null)throw new IllegalStateException("System denied screen capture or returned no buffer.");
                Class<?> sc=screenshot.getClass();
                buffer=(HardwareBuffer)sc.getMethod("getHardwareBuffer").invoke(screenshot);
                if(buffer==null)throw new IllegalStateException("Empty capture buffer.");
                boolean secure=(boolean)sc.getMethod("containsSecureLayers").invoke(screenshot);
                if(secure||(buffer.getUsage()&HardwareBuffer.USAGE_PROTECTED_CONTENT)!=0)
                    throw new IllegalStateException("Protected content: returning to original display.");
                if(!running)return;
                // Ownership of the buffer moves to the app; it closes it after rendering.
                if(listener!=null) { listener.onBuffer(buffer,++frames,SystemClock.uptimeMillis()); sent=true; }
                if(listener!=null) listener.onFrame(frames,SystemClock.uptimeMillis());
            } catch(Throwable e) {fail(e);return;}
            finally { if(buffer!=null&&!sent) buffer.close(); }
            if(running)handler.postDelayed(this,Math.max(1,interval-(SystemClock.uptimeMillis()-started)));
        }
    };

    @Override public synchronized boolean inject(MotionEvent event) {
        if(!running||inject==null||event==null)return false;
        try {
            boolean ok=(boolean)inject.invoke(inputManager,event,0);if(!ok)return false;
            if(lastTouch!=null)lastTouch.recycle();
            lastTouch=(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)
                    ?null:MotionEvent.obtain(event);return true;
        } catch(Throwable e) {error=describe(e);return false;}
    }
    @Override public Bundle status() {
        Bundle b=new Bundle();b.putBoolean("running",running);b.putLong("frames",frames);
        b.putString("error",error);b.putString("backend",backendName());return b;
    }
    @Override public void heartbeat() {heartbeat=SystemClock.uptimeMillis();}
    @Override public void stop() {running=false;cancelTouch();handler.post(this::release);}
    private synchronized void cancelTouch() {
        if(lastTouch!=null) {
            try {lastTouch.setAction(MotionEvent.ACTION_CANCEL);inject.invoke(inputManager,lastTouch,0);}
            catch(Throwable ignored) {} finally {lastTouch.recycle();lastTouch=null;}
        }
    }
    private void fail(Throwable e) {
        error=describe(e);running=false;cancelTouch();
        try {if(listener!=null)listener.onError(error);}catch(Exception ignored){}
        release();
    }
    private void release() {
        running=false;handler.removeCallbacks(frame);cancelTouch();
        if(exclude!=null){exclude.release();exclude=null;}
        captureArgs=null;listener=null;
    }
    @Override public void destroy() {stop();handler.post(()->{thread.quitSafely();System.exit(0);});}
    private String backendName() {
        return switch(backend) {
            case IWM_CAPTURE -> "IWindowManager";
            case SURFACE_CAPTURE -> "SurfaceControl";
            default -> "unavailable";
        };
    }
    private static String describe(Throwable t) {
        while(t instanceof InvocationTargetException&&t.getCause()!=null)t=t.getCause();
        return t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());
    }
}

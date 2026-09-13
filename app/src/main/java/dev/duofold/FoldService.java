package dev.duofold;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import dev.duofold.bridge.BridgeClient;
import dev.duofold.bridge.ICaptureListener;
import dev.duofold.bridge.IFoldBridge;
import dev.duofold.motion.FoldModel;
import dev.duofold.motion.TiltSensor;
import dev.duofold.render.FoldSurface;
import dev.duofold.render.FoldView;
import dev.duofold.render.FoldViewVk;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class FoldService extends AccessibilityService {
    private static volatile FoldService instance;
    public static FoldService current() { return instance; }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService touches=Executors.newSingleThreadExecutor();
    private final AtomicInteger queuedTouches=new AtomicInteger();
    private BridgeClient client;
    private FoldSurface view;
    private TiltSensor sensor;
    private WindowManager wm;
    private boolean active,captureReady,intercepting;
    private volatile int generation;
    private long lastFrame,started,trialEnd,frames;
    private float progress=1,intensity=1;
    private String status="尚未开启";
    private final Runnable bridgeChanged=()-> {
        if(active && client.service()==null) stopSession("Shizuku 断开，已恢复原始屏幕");
    };
    private final BroadcastReceiver screenOff=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { stopSession("屏幕已关闭"); }
    };
    @Override protected void onServiceConnected() {
        instance=this;wm=getSystemService(WindowManager.class);client=BridgeClient.get(this);
        client.addObserver(bridgeChanged);client.connect();setIntercept(false);
        if(Build.VERSION.SDK_INT>=33)
            registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }
    public boolean isActive() { return active; }
    public String status() { return status; }
    public float progress() { return progress; }
    public long frames() { return frames; }
    public String diagnostics() { return "active="+active+" ready="+captureReady+" intercept="+intercepting
            +" size="+(view==null?"none":view.getWidth()+"x"+view.getHeight())+" progress="+progress
            +" frames="+frames+" touches="+touchCount+" status="+status; }
    private long touchCount;
    private boolean discardGesture;
    private boolean debugTilt;
    public void testAngle(float degrees) {
        testAxes(0,degrees);
    }
    public void testAxes(float forwardDegrees,float sideDegrees) {
        if(BuildConfig.DEBUG && view!=null) {debugTilt=true;view.setFold(
                (float)Math.toRadians(forwardDegrees),(float)Math.toRadians(sideDegrees),intensity);}
    }
    public void saveDebugFrame() {if(BuildConfig.DEBUG && view!=null)view.saveDebugFrame();}
    public void calibrate() { if(sensor!=null) {sensor.calibrate();progress=1;view.setFold(0,0,intensity);} }
    public void startSession(boolean trial) {
        if(active) return;
        IFoldBridge bridge=client.service();
        if(bridge==null) {status="先完成 Shizuku 授权";Toast.makeText(this,status,Toast.LENGTH_LONG).show();return;}
        if(getSystemService(android.view.accessibility.AccessibilityManager.class).isTouchExplorationEnabled()) {
            status="当前有触摸探索服务运行，请先关闭以避免手势冲突";
            Toast.makeText(this,status,Toast.LENGTH_LONG).show();return;
        }
        active=true;captureReady=false;generation++;lastFrame=0;frames=0;
        debugTilt=false;
        touchCount=0;
        started=SystemClock.uptimeMillis();trialEnd=trial?started+10000:0;
        intensity=getSharedPreferences("duo",0).getFloat("intensity",1);
        float range=getSharedPreferences("duo",0).getFloat("range",80);
        boolean includeZ=getSharedPreferences("duo",0).getBoolean("zAxis",false);
        status="正在取得真实屏幕画面";
        try {
            addOverlay();
            sensor=new TiltSensor(this,range,includeZ,(p,forwardDegrees,sideDegrees)-> {
                progress=p;
                if(view!=null && active && !debugTilt) view.setFold(
                        (float)Math.toRadians(forwardDegrees),(float)Math.toRadians(sideDegrees),intensity);
            });
            if(!sensor.start()) throw new IllegalStateException("没有可用的姿态传感器");
            showNotification();main.post(watchdog);
        } catch(Exception e) {stopSession(e.toString());}
    }
    private void addOverlay() {
        int overlayGeneration=generation;
        boolean useVulkan="vk".equals(getSharedPreferences("duo",0).getString("renderer","gl"));
        FoldSurface.Listener listener=new FoldSurface.Listener() {
            @Override public void onReady(int w,int h) {
                if(active && overlayGeneration==generation) beginCapture(w,h);
            }
            @Override public void onFirstFrame() {
                if(Build.VERSION.SDK_INT<31 && active && overlayGeneration==generation && !captureReady) {
                    captureReady=true;
                    if(view!=null)view.setAlpha(1f);
                    status=runningStatus();
                }
            }
            @Override public void onError(String message) {
                if(overlayGeneration==generation) stopSession(message);
            }
        };
        view=useVulkan?new FoldViewVk(this,listener):new FoldView(this,listener);
        view.setBlurScale(getSharedPreferences("duo",0).getFloat("blurScale",1));
        view.setStretch(getSharedPreferences("duo",0).getFloat("stretch",1));
        int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;lp.setTitle("Duo Fold optical layer");
        lp.layoutInDisplayCutoutMode=Build.VERSION.SDK_INT>=30
                ?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                :WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if(Build.VERSION.SDK_INT>=30)lp.setFitInsetsTypes(0);
        // Android 10/11 cannot exclude an individual SurfaceControl from a display capture.
        // Keep the optical layer transparent until its one clean source frame has arrived.
        if(Build.VERSION.SDK_INT<31)view.setAlpha(0f);
        wm.addView((android.view.View)view,lp);
    }
    private void beginCapture(int w,int h) {
        if(!active || view==null) return;
        setIntercept(false);captureReady=false;started=SystemClock.uptimeMillis();lastFrame=0;
        SurfaceControl exclude=view.getSurfaceControl();
        if(exclude==null || !exclude.isValid()) {stopSession("无法取得显示层，已取消采集以避免画面递归");return;}
        // Force the display to stay at its high refresh rate while the effect is active,
        // so the system's idle/adaptive policy doesn't drop it to 60Hz.
        if(Build.VERSION.SDK_INT>=30) {
            try {
                try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) {
                    transaction.setFrameRate(exclude,120f,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ALWAYS);
                    transaction.apply();
                }
            } catch(Exception ignored) {}
        }
        Bundle options=new Bundle();options.putParcelable("exclude",exclude);
        options.putInt("width",w);options.putInt("height",h);options.putInt("fps",getSharedPreferences("duo",0).getInt("fps",60));
        options.putInt("displayWidth",view.getWidth());options.putInt("displayHeight",view.getHeight());
        int session=generation;
        io.execute(()-> {
            try {
                IFoldBridge bridge=client.service();
                if(bridge==null || session!=generation) return;
                bridge.start(options,new ICaptureListener.Stub() {
                    @Override public void onFrame(long sequence,long timestampMs) {
                        main.post(()-> {
                            if(!active || session!=generation) return;
                            lastFrame=SystemClock.uptimeMillis();frames=sequence;
                            if(Build.VERSION.SDK_INT>=31 && !captureReady) {
                                captureReady=true;
                                setIntercept(true);status=runningStatus();
                            }
                        });
                    }
                    @Override public void onBuffer(android.hardware.HardwareBuffer buffer,long sequence,long timestampMs) {
                        if(session==generation && active && view!=null) view.queueBuffer(buffer);
                        else buffer.close();
                    }
                    @Override public void onError(String message) {main.post(()->{if(session==generation) stopSession(message);});}
                });
            } catch(Exception e) {main.post(()->{if(session==generation) stopSession(e.toString());});}
        });
    }
    private final Runnable watchdog=new Runnable() {
        @Override public void run() {
            if(!active) return;
            long now=SystemClock.uptimeMillis();
            if(trialEnd>0 && now>=trialEnd) {stopSession("10 秒体验结束，已恢复原始屏幕");return;}
            boolean liveCapture=Build.VERSION.SDK_INT>=31;
            if((liveCapture && lastFrame>0 && now-lastFrame>1500) || (lastFrame==0 && now-started>5000)) {
                stopSession("画面更新超时，已恢复原始屏幕");return;
            }
            IFoldBridge bridge=client.service();
            if(bridge==null) {stopSession("Shizuku 服务断开");return;}
            io.execute(()->{try{bridge.heartbeat();}catch(Exception e){main.post(()->stopSession("心跳中断"));}});
            main.postDelayed(this,500);
        }
    };
    @Override public void onMotionEvent(MotionEvent event) {
        touchCount++;
        if(!active || !captureReady || view==null) return;
        if(event.getPointerCount()>=3) {stopSession("三指退出，已恢复原始屏幕");return;}
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) discardGesture=false;
        int n=event.getPointerCount();
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[n];
        FoldModel.State state=view.inputState();
        float width=Math.max(1,view.getWidth()),height=Math.max(1,view.getHeight());
        for(int i=0;i<n;i++) {
            props[i]=new MotionEvent.PointerProperties();event.getPointerProperties(i,props[i]);
            coords[i]=new MotionEvent.PointerCoords();event.getPointerCoords(i,coords[i]);
            float[] uv=FoldModel.sourceAt(coords[i].x/width,coords[i].y/height,state);
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN &&
                    (uv[0]<0 || uv[0]>1 || uv[1]<0 || uv[1]>1)) {
                discardGesture=true;return;
            }
            coords[i].x=FoldModel.clamp(uv[0])*width;coords[i].y=FoldModel.clamp(uv[1])*height;
        }
        MotionEvent mapped=MotionEvent.obtain(event.getDownTime(),event.getEventTime(),event.getAction(),n,props,coords,
                event.getMetaState(),event.getButtonState(),event.getXPrecision(),event.getYPrecision(),
                event.getDeviceId(),event.getEdgeFlags(),InputDevice.SOURCE_TOUCHSCREEN,event.getFlags());
        if(discardGesture) {mapped.recycle();return;}
        if(queuedTouches.incrementAndGet()>16) {
            queuedTouches.decrementAndGet();mapped.recycle();stopSession("触控队列超时，已恢复原始屏幕");return;
        }
        int session=generation;
        touches.execute(()-> {
            try {
                IFoldBridge bridge=client.service();
                if(session==generation && (bridge==null || !bridge.inject(mapped)))
                    main.post(()->{if(session==generation) stopSession("系统拒绝触控注入；检查 USB 调试（安全设置）");});
            } catch(Exception e) {main.post(()->{if(session==generation)stopSession("触控连接中断");});}
            finally {mapped.recycle();queuedTouches.decrementAndGet();}
        });
    }
    private void setIntercept(boolean enabled) {
        if(Build.VERSION.SDK_INT<34) {intercepting=false;return;}
        AccessibilityServiceInfo info=getServiceInfo();
        if(info==null) return;
        info.setMotionEventSources(enabled?InputDevice.SOURCE_TOUCHSCREEN:0);
        setServiceInfo(info);intercepting=enabled;
    }
    public void stopSession(String reason) {
        // Release original input before doing any binder work or GPU cleanup.
        setIntercept(false);active=false;captureReady=false;generation++;status=reason;
        android.util.Log.i("DuoFold", "Stopped: "+reason+"; frames="+frames+" touches="+touchCount);
        main.removeCallbacks(watchdog);
        if(sensor!=null) {sensor.stop();sensor=null;}
        if(view!=null) {
            FoldSurface old=view;view=null;
            old.releaseResources();old.onPause();
            try {wm.removeViewImmediate((android.view.View)old);}catch(Exception ignored){}
        }
        IFoldBridge bridge=client==null?null:client.service();
        if(bridge!=null) io.execute(()->{try{bridge.stop();}catch(Exception ignored){}});
        getSystemService(NotificationManager.class).cancel(81);
        getSharedPreferences("duo",0).edit().putString("lastStatus",reason).apply();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if(active) {
            setIntercept(false);captureReady=false;lastFrame=0;started=SystemClock.uptimeMillis();generation++;
            if(sensor!=null)sensor.calibrate();
            FoldSurface old=view;view=null;
            if(old!=null) {
                old.releaseResources();old.onPause();
                try {wm.removeViewImmediate((android.view.View)old);}catch(Exception ignored){}
            }
            IFoldBridge bridge=client==null?null:client.service();
            if(bridge!=null)io.execute(()->{try{bridge.stop();}catch(Exception ignored){}});
            try {addOverlay();status="屏幕方向已切换，正在重新校准";}
            catch(Exception e){stopSession("横竖屏切换失败："+e.getMessage());}
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {stopSession("辅助服务被中断");}
    @Override public boolean onUnbind(Intent intent) {stopSession("辅助服务已关闭");return super.onUnbind(intent);}
    @Override public void onDestroy() {
        stopSession("服务已停止");instance=null;
        if(client!=null)client.removeObserver(bridgeChanged);
        try{unregisterReceiver(screenOff);}catch(Exception ignored){}
        io.shutdown();touches.shutdown();super.onDestroy();
    }
    private void showNotification() {
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("duo_live","展开效果",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getBroadcast(this,1,new Intent(this,StopReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"duo_live").setSmallIcon(R.drawable.ic_fold)
                .setContentTitle("Duo Fold 正在运行").setContentText(Build.VERSION.SDK_INT>=34
                        ?"倾斜手机展开 · 三指触屏退出":"倾斜手机展开 · 点此返回或使用停止按钮")
                .setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
        nm.notify(81,notification);
    }
    private String runningStatus() {
        return Build.VERSION.SDK_INT>=34?"运行中 · 三指同时触屏可退出":"运行中 · 使用通知栏按钮退出";
    }
}

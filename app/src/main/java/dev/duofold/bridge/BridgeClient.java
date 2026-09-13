package dev.duofold.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

public final class BridgeClient {
    private static BridgeClient instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> observers=new CopyOnWriteArrayList<>();
    private final Shizuku.UserServiceArgs args;
    private volatile IFoldBridge bridge;
    private volatile String message="等待 Shizuku";
    private boolean binding;
    private BridgeClient(Context context) {
        args=new Shizuku.UserServiceArgs(new ComponentName(context,CaptureBridge.class))
                .daemon(false).processNameSuffix("capture").debuggable(true).version(1);
        Shizuku.addBinderReceivedListenerSticky(()->main.post(this::connect));
        Shizuku.addBinderDeadListener(()->main.post(()-> {
            bridge=null; binding=false; message="Shizuku 已断开"; notifyObservers();
        }));
        Shizuku.addRequestPermissionResultListener((code,result)-> {
            if(result==PackageManager.PERMISSION_GRANTED) connect();
            else { message="未获得 Shizuku 授权"; notifyObservers(); }
        });
    }
    public static synchronized BridgeClient get(Context context) {
        if(instance==null) instance=new BridgeClient(context.getApplicationContext()); return instance;
    }
    public IFoldBridge service() { return bridge; }
    public String message() { return message; }
    public void addObserver(Runnable observer) { observers.add(observer); }
    public void removeObserver(Runnable observer) { observers.remove(observer); }
    public void request() {
        try {
            if(!Shizuku.pingBinder()) {message="请先启动 Shizuku";notifyObservers();return;}
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(41);
            else connect();
        } catch(Exception e) {message=e.toString();notifyObservers();}
    }
    public void connect() {
        try {
            if(bridge!=null || binding || !Shizuku.pingBinder()) return;
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                message="Shizuku 已启动，等待授权";notifyObservers();return;
            }
            binding=true; message="正在连接系统画面服务";notifyObservers();
            Shizuku.bindUserService(args,connection);
        } catch(Exception e) {binding=false;message=e.toString();notifyObservers();}
    }
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            IFoldBridge candidate=IFoldBridge.Stub.asInterface(binder);
            worker.execute(()-> {
                try {
                    Bundle probe=candidate.probe();
                    boolean ready=probe.getBoolean("captureApi") && probe.getBoolean("inputApi");
                    main.post(()-> {binding=false;bridge=ready?candidate:null;
                        message=ready?"Shizuku 就绪 · UID "+probe.getInt("uid"):probe.getString("message");notifyObservers();});
                } catch(Exception e) {main.post(()->{binding=false;message=e.toString();notifyObservers();});}
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bridge=null;binding=false;message="系统画面服务已断开";notifyObservers();
        }
    };
    private void notifyObservers() { main.post(()->observers.forEach(Runnable::run)); }
}

package dev.duofold.bridge;
import android.os.Bundle;
import android.view.MotionEvent;
import dev.duofold.bridge.ICaptureListener;
interface IFoldBridge {
    Bundle probe() = 0;
    void start(in Bundle options, ICaptureListener listener) = 1;
    boolean inject(in MotionEvent event) = 2;
    Bundle status() = 3;
    void heartbeat() = 4;
    void stop() = 5;
    void destroy() = 16777114;
}

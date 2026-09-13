package dev.duofold.bridge;
import android.hardware.HardwareBuffer;
oneway interface ICaptureListener {
    void onFrame(long sequence, long timestampMs);
    void onBuffer(in HardwareBuffer buffer, long sequence, long timestampMs);
    void onError(String message);
}

package dev.duofold;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        FoldService service=FoldService.current();
        if(service!=null) service.stopSession("已手动停止");
    }
}

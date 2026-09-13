package dev.duofold;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Debug APK only. DUMP restricts entry to the authorized shell/system. */
public final class DiagnosticReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        FoldService service=FoldService.current();
        if(service==null) { setResultData("service disconnected"); return; }
        switch(intent.getStringExtra("command") == null ? "status" : intent.getStringExtra("command")) {
            case "trial": service.startSession(true); break;
            case "start": service.startSession(false); break;
            case "stop": service.stopSession("测试已停止"); break;
            case "calibrate": service.calibrate(); break;
            case "angle": service.testAxes(intent.getFloatExtra("forward",0),
                    intent.getFloatExtra("side",intent.getFloatExtra("degrees",0))); break;
            case "snapshot": service.saveDebugFrame();break;
            default: break;
        }
        String result=service.diagnostics();
        Log.i("DuoFold",result);setResultData(result);
    }
}

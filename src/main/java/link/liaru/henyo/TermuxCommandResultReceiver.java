package link.liaru.henyo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class TermuxCommandResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TermuxCommandBridge.onResult(intent);
    }
}

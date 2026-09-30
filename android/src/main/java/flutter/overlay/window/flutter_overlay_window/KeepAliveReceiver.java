package flutter.overlay.window.flutter_overlay_window;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Reergue o serviço quando o processo morreu sem o entregador ficar offline:
 * reinício do aparelho, atualização do app e a verificação periódica (que
 * pega o processo morto pelo fabricante ou pelo "Parar" do gerenciador).
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!KeepAlive.isEnabled(context)) return;

        if (KeepAlive.ACTION_WATCHDOG.equals(action)) {
            KeepAlive.scheduleWatchdog(context, KeepAlive.WATCHDOG_INTERVAL_MS);
        }
        if (OverlayService.serviceAlive) return;

        if (KeepAlive.isStale(context)) {
            Log.i("OverlayKeepAlive", "Serviço parado há tempo demais, desligando o manter vivo (" + action + ")");
            KeepAlive.disable(context);
            return;
        }
        KeepAlive.startRestore(context, action != null ? action : "receiver");
    }
}

package flutter.overlay.window.flutter_overlay_window;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

/**
 * Estado "manter vivo": enquanto o app diz que o entregador está online, o
 * OverlayService fica de pé mesmo com o app aberto (janela escondida), e é
 * reerguido depois de o processo morrer — reinício do aparelho, atualização,
 * processo morto pelo fabricante, "Parar" do gerenciador de apps ativos.
 *
 * Tudo mora em SharedPreferences porque quem lê é um processo novo, sem Dart.
 */
public final class KeepAlive {
    private static final String TAG = "OverlayKeepAlive";
    private static final String PREFS = "flutter_overlay_window_keep_alive";

    private static final String K_ENABLED = "enabled";
    private static final String K_TITLE = "title";
    private static final String K_CONTENT = "content";
    private static final String K_WIDTH = "width";
    private static final String K_HEIGHT = "height";
    private static final String K_X = "x";
    private static final String K_Y = "y";
    private static final String K_LAST_ALIVE = "last_alive";

    static final String ACTION_KEEP_ALIVE = "flutter.overlay.window.KEEP_ALIVE";
    static final String ACTION_RESTORE = "flutter.overlay.window.RESTORE";
    static final String ACTION_WATCHDOG = "flutter.overlay.window.WATCHDOG";

    static final long WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L;

    // Passado disso sem o serviço de pé, o servidor já tirou o entregador de
    // online (tempo_offline_sem_localizacao, 30 min por padrão): reerguer seria
    // mandar localização e mostrar "conectado" para quem não está mais online.
    static final long MAX_RESTORE_GAP_MS = 30 * 60 * 1000L;

    private KeepAlive() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(K_ENABLED, false);
    }

    static void enable(Context context, String title, String content, int width, int height, int x, int y) {
        prefs(context).edit()
                .putBoolean(K_ENABLED, true)
                .putString(K_TITLE, title)
                .putString(K_CONTENT, content)
                .putInt(K_WIDTH, width)
                .putInt(K_HEIGHT, height)
                .putInt(K_X, x)
                .putInt(K_Y, y)
                .putLong(K_LAST_ALIVE, System.currentTimeMillis())
                .apply();
    }

    static void disable(Context context) {
        prefs(context).edit().putBoolean(K_ENABLED, false).apply();
        cancelWatchdog(context);
    }

    static void touch(Context context) {
        prefs(context).edit().putLong(K_LAST_ALIVE, System.currentTimeMillis()).apply();
    }

    /** Relógio de parede: sobrevive ao reinício do aparelho, ao contrário do elapsedRealtime. */
    static boolean isStale(Context context) {
        long last = prefs(context).getLong(K_LAST_ALIVE, 0L);
        return last == 0L || System.currentTimeMillis() - last > MAX_RESTORE_GAP_MS;
    }

    static void savePosition(Context context, int x, int y) {
        prefs(context).edit().putInt(K_X, x).putInt(K_Y, y).apply();
    }

    static String title(Context context) {
        return prefs(context).getString(K_TITLE, WindowSetup.overlayTitle);
    }

    static String content(Context context) {
        return prefs(context).getString(K_CONTENT, WindowSetup.overlayContent);
    }

    /** Janelinha salva no último setKeepAlive, no formato que o initOverlay lê. */
    static Intent windowIntent(Context context) {
        SharedPreferences p = prefs(context);
        WindowSetup.width = p.getInt(K_WIDTH, WindowManager.LayoutParams.MATCH_PARENT);
        WindowSetup.height = p.getInt(K_HEIGHT, WindowManager.LayoutParams.MATCH_PARENT);
        WindowSetup.enableDrag = true;
        WindowSetup.positionGravity = "none";
        WindowSetup.setGravityFromAlignment("topRight");
        WindowSetup.setFlag("defaultFlag");
        WindowSetup.overlayTitle = title(context);
        WindowSetup.overlayContent = content(context);

        Intent intent = new Intent(context, OverlayService.class);
        intent.putExtra("startX", p.getInt(K_X, OverlayConstants.DEFAULT_XY));
        intent.putExtra("startY", p.getInt(K_Y, OverlayConstants.DEFAULT_XY));
        intent.putExtra("width", WindowSetup.width);
        intent.putExtra("height", WindowSetup.height);
        intent.putExtra("enableDrag", true);
        intent.putExtra("alignment", "topRight");
        return intent;
    }

    /**
     * Sobe o serviço já com a janelinha. Quem chama precisa de uma das isenções
     * do Android 12+ para abrir serviço em primeiro plano a partir do segundo
     * plano (boot, atualização, push de prioridade alta, permissão de sobreposição
     * até o Android 14) — sem ela, o Android recusa e fica só o log.
     */
    static boolean startRestore(Context context, String motivo) {
        try {
            Intent intent = new Intent(context, OverlayService.class);
            intent.setAction(ACTION_RESTORE);
            intent.putExtra("motivo", motivo);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "Serviço reerguido (" + motivo + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível reerguer o serviço (" + motivo + "): " + e.getMessage());
            return false;
        }
    }

    static void scheduleWatchdog(Context context, long delayMs) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;
            long at = SystemClock.elapsedRealtime() + delayMs;
            PendingIntent pi = watchdogIntent(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível agendar a verificação do serviço: " + e.getMessage());
        }
    }

    static void cancelWatchdog(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.cancel(watchdogIntent(context));
            }
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível cancelar a verificação do serviço: " + e.getMessage());
        }
    }

    private static PendingIntent watchdogIntent(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class);
        intent.setAction(ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 4580, intent, flags);
    }

    // --- Visibilidade do app -------------------------------------------------
    // A janelinha só pode subir sozinha quando nenhuma tela do app está à vista;
    // por cima do app aberto ela cobriria a tela de quem está usando.

    private static int startedActivities = 0;
    private static boolean callbacksRegistered = false;

    static synchronized void registerVisibilityCallbacks(Context context) {
        if (callbacksRegistered) return;
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) return;
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(android.app.Activity a) {
                if (!(a instanceof LockScreenOverlayActivity)) startedActivities++;
            }
            @Override public void onActivityResumed(android.app.Activity a) {}
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) {
                if (!(a instanceof LockScreenOverlayActivity) && startedActivities > 0) startedActivities--;
            }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
        callbacksRegistered = true;
    }

    static boolean appVisible() {
        return startedActivities > 0;
    }
}

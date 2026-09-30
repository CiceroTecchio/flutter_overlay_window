package flutter.overlay.window.flutter_overlay_window;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationManagerCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.app.usage.UsageStatsManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import java.lang.reflect.Method;

public class FlutterOverlayWindowPlugin implements
        FlutterPlugin, ActivityAware, BasicMessageChannel.MessageHandler, MethodCallHandler,
        PluginRegistry.ActivityResultListener {

    private static final String OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background";
    private static final String OPSTR_RUN_IN_BACKGROUND = "android:run_in_background";
    private static final int STANDBY_BUCKET_NEVER = 50;

    private static final long MIUI_PROVIDER_RETRY_WINDOW_MS = 10 * 60 * 1000L; // 10 min

    private MethodChannel channel;
    private Context context;
    private Activity mActivity;
    private BasicMessageChannel<Object> messenger;
    private Result pendingResult;
    final int REQUEST_CODE_FOR_OVERLAY_PERMISSION = 1248;
    
    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();
        KeepAlive.registerVisibilityCallbacks(this.context);

        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.CHANNEL_TAG);
        channel.setMethodCallHandler(this);

        messenger = new BasicMessageChannel<Object>(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.MESSENGER_TAG,
                JSONMessageCodec.INSTANCE);

        WindowSetup.setMessenger(messenger);
        if (messenger != null) {
            messenger.setMessageHandler(this);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("checkPermission")) {
            result.success(checkOverlayPermission());
        } else if (call.method.equals("isLockScreenPermissionGranted")) {
            result.success(isLockScreenPermissionGranted());
        } else if (call.method.equals("openLockScreenPermissionSettings")) {
            openLockScreenPermissionSettings();
            result.success(null);
        } else if (call.method.equals("requestPermission")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pendingResult = result;
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.parse("package:" + mActivity.getPackageName()));
                mActivity.startActivityForResult(intent, REQUEST_CODE_FOR_OVERLAY_PERMISSION);
            } else {
                result.success(true);
            }
        } else if (call.method.equals("showOverlay")) {
            Log.i("FlutterOverlayWindowPlugin", "🎬 showOverlay() - Iniciando overlay");
            Log.d("FlutterOverlayWindowPlugin", "📊 Estado atual do OverlayService - isRunning: " + OverlayService.isRunning);
            Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 1: showOverlay() chamado com sucesso");
            
            if (!checkOverlayPermission()) {
                Log.w("FlutterOverlayWindowPlugin", "⚠️ Permissão de overlay não concedida");
                Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 2: Falha na verificação de permissão");
                result.error("PERMISSION", "overlay permission is not enabled", null);
                return;
            }
            Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 2: Permissão de overlay verificada com sucesso");
            Integer height = call.argument("height");
            Integer width = call.argument("width");
            String alignment = call.argument("alignment");
            String flag = call.argument("flag");
            String overlayTitle = call.argument("overlayTitle");
            String overlayContent = call.argument("overlayContent");
            String notificationVisibility = call.argument("notificationVisibility");
            boolean enableDrag = call.argument("enableDrag");
            String positionGravity = call.argument("positionGravity");
            Map<String, Integer> startPosition = call.argument("startPosition");
            int startX = startPosition != null ? startPosition.getOrDefault("x", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
            int startY = startPosition != null ? startPosition.getOrDefault("y", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
            
            Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 3: Parâmetros extraídos com sucesso");

            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

            boolean isScreenOff = powerManager != null && !powerManager.isInteractive();
            boolean isLocked = keyguardManager != null && keyguardManager.isKeyguardLocked();
            
            Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 4: Verificações de tela concluídas - isScreenOff: " + isScreenOff + ", isLocked: " + isLocked);
            Log.d("FlutterOverlayWindowPlugin", "🔍 Flag recebido: " + flag);

            boolean lockScreenIntent = false;

            String lockScreenFlag = flag;

            if ("lockScreen".equals(flag) && (isScreenOff || isLocked)) {
                lockScreenIntent = true;
                Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 5: LockScreen intent ativado - flag=lockScreen e tela bloqueada");
            } else {
                if (flag == null || "lockScreen".equals(flag)) {
                    lockScreenFlag = "flagNotFocusable";
                }
                Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 5: Overlay normal será usado - flag=" + flag + ", lockScreenIntent=" + lockScreenIntent);
            }

            WindowSetup.width = width != null ? width : -1;
            WindowSetup.height = height != null ? height : -1;
            WindowSetup.enableDrag = enableDrag;
            WindowSetup.setGravityFromAlignment(alignment != null ? alignment : "center");
            WindowSetup.setFlag(lockScreenFlag);
            WindowSetup.overlayTitle = overlayTitle;
            WindowSetup.overlayContent = overlayContent == null ? "" : overlayContent;
            WindowSetup.positionGravity = positionGravity;
            WindowSetup.setNotificationVisibility(notificationVisibility);
            
            Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 6: WindowSetup configurado com sucesso");

            if (lockScreenIntent) {
                Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 6A: Usando LockScreenOverlayActivity");
                if (LockScreenOverlayActivity.isRunning) {
                    Log.d("OverlayPlugin", "LockScreenOverlay já está rodando, trazendo para frente.");
                    
                    Intent bringToFront = new Intent(context, LockScreenOverlayActivity.class);
                    bringToFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    bringToFront.putExtra("startX", startX);
                    bringToFront.putExtra("startY", startY);
                    bringToFront.putExtra("width", width);
                    bringToFront.putExtra("height", height);
                    bringToFront.putExtra("enableDrag", enableDrag);
                    bringToFront.putExtra("alignment", alignment);
                    bringToFront.putExtra("overlayTitle", overlayTitle);
                    bringToFront.putExtra("overlayContent", overlayContent);
                    context.startActivity(bringToFront);
                } else {
                    Log.d("OverlayPlugin", "Iniciando LockScreenOverlayActivity");
                    // Abrir a activity com overlay na tela de bloqueio
                    Intent lockIntent = new Intent(context, LockScreenOverlayActivity.class);
                    lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    lockIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    lockIntent.putExtra("startX", startX);
                    lockIntent.putExtra("startY", startY);
                    lockIntent.putExtra("width", width);
                    lockIntent.putExtra("height", height);
                    lockIntent.putExtra("enableDrag", enableDrag);
                    lockIntent.putExtra("alignment", alignment);
                    lockIntent.putExtra("overlayTitle", overlayTitle);
                    lockIntent.putExtra("overlayContent", overlayContent);
                    context.startActivity(lockIntent);
                }
            } else {
                Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 6B: Usando OverlayService normal");
                
                // Check foreground service permissions for Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (!hasForegroundServicePermission()) {
                        Log.e("FlutterOverlayWindowPlugin", "❌ FOREGROUND_SERVICE permission is required to start overlay service");
                        result.error("PERMISSION_ERROR", "FOREGROUND_SERVICE permission is required", null);
                        return;
                    }

                    boolean isAppInForeground = isAppInForeground();
                    Log.d("FlutterOverlayWindowPlugin", "🔍 App in foreground: " + isAppInForeground);
                    if (!isAppInForeground) {
                        Log.i("FlutterOverlayWindowPlugin", "ℹ️ Starting overlay while app is backgrounded; relying on user interaction to satisfy OS restrictions");
                    }
                }
                
                OverlayService vivo = OverlayService.instance();
                if (OverlayService.serviceAlive && vivo != null) {
                    // Com o serviço de pé não há o que iniciar: pedir de novo ao Android
                    // passaria pela restrição de iniciar serviço a partir do segundo plano,
                    // que no Android 15 exige janela já visível — justo o que falta aqui.
                    try {
                        final Intent intent = new Intent(context, OverlayService.class);
                        intent.putExtra("startX", startX);
                        intent.putExtra("startY", startY);
                        intent.putExtra("width", width);
                        intent.putExtra("height", height);
                        intent.putExtra("enableDrag", enableDrag);
                        intent.putExtra("alignment", alignment);
                        intent.putExtra("overlayTitle", overlayTitle);
                        intent.putExtra("overlayContent", overlayContent);
                        vivo.initOverlay(intent);
                        result.success(null);
                    } catch (Exception e) {
                        Log.e("FlutterOverlayWindowPlugin", "❌ Falha ao subir a janela no serviço vivo: " + e.getMessage(), e);
                        result.error("SERVICE_ERROR", "Failed to show overlay window", e.getMessage());
                    }
                    return;
                }

                try {
                    Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 7: Iniciando OverlayService normal");
                    Log.d("FlutterOverlayWindowPlugin", "🚀 Iniciando OverlayService normal");
                    
                    final Intent intent = new Intent(context, OverlayService.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra("startX", startX);
                    intent.putExtra("startY", startY);
                    intent.putExtra("width", width);
                    intent.putExtra("height", height);
                    intent.putExtra("enableDrag", enableDrag);
                    intent.putExtra("alignment", alignment);
                    intent.putExtra("overlayTitle", overlayTitle);
                    intent.putExtra("overlayContent", overlayContent);
                    
                    Log.d("FlutterOverlayWindowPlugin", "📦 Parâmetros enviados - Width: " + width + ", Height: " + height + ", StartX: " + startX + ", StartY: " + startY);
                    
                    Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 10: Chamando context.startService()");
                    Log.d("FlutterOverlayWindowPlugin", "🚀 Chamando context.startService()...");
                    
                    // Use startForegroundService() for Android 8+ to ensure proper foreground service handling
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d("FlutterOverlayWindowPlugin", "🚀 Using startForegroundService() for Android 8+");
                        context.startForegroundService(intent);
                    } else {
                        Log.d("FlutterOverlayWindowPlugin", "🚀 Using startService() for older Android versions");
                        context.startService(intent);
                    }
                    
                    Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 11: startService() executado com sucesso");
                    Log.d("FlutterOverlayWindowPlugin", "✅ OverlayService.startService() chamado com sucesso");
                    
                    // Verificar se o service está rodando após a chamada
                    Log.d("FlutterOverlayWindowPlugin", "📊 Estado após startService - isRunning: " + OverlayService.isRunning);
                } catch (Exception e) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e instanceof android.app.ForegroundServiceStartNotAllowedException) {
                        Log.e("FlutterOverlayWindowPlugin", "❌ ForegroundServiceStartNotAllowedException ao iniciar OverlayService", e);
                        result.error("SERVICE_BACKGROUND_RESTRICTION", "Foreground service start is restricted while app is in background", e.getMessage());
                        return;
                    } else if (e instanceof SecurityException) {
                        Log.e("FlutterOverlayWindowPlugin", "❌ SecurityException ao iniciar OverlayService", e);
                        result.error("SERVICE_SECURITY_ERROR", "Failed to start overlay service due to security restrictions", e.getMessage());
                        return;
                    }

                    Log.e("FlutterOverlayWindowPlugin", "❌ Falha ao iniciar OverlayService: " + e.getMessage(), e);
                    result.error("SERVICE_ERROR", "Failed to start overlay service", e.getMessage());
                    return;
                }
            }
            Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 12: showOverlay() concluído com sucesso");
            Log.i("FlutterOverlayWindowPlugin", "✅ showOverlay() - Overlay iniciado com sucesso");
            result.success(null);
        } else if (call.method.equals("setKeepAlive")) {
            setKeepAlive(call, result);
            return;
        } else if (call.method.equals("restoreService")) {
            restoreService(call, result);
            return;
        } else if (call.method.equals("isServiceRunning")) {
            result.success(OverlayService.serviceAlive);
            return;
        } else if (call.method.equals("isOverlayActive")) {
            result.success(OverlayService.isRunning || LockScreenOverlayActivity.isRunning);
            return;
        } else if (call.method.equals("moveOverlay")) {
            // Only move overlay if it's actually running
            if (OverlayService.isRunning) {
                try {
                    int x = call.argument("x");
                    int y = call.argument("y");
                    OverlayService.moveOverlay(x, y);
                    result.success(true);
                } catch (Exception e) {
                    Log.e("OverlayPlugin", "Failed to move overlay: " + e.getMessage());
                    e.printStackTrace();
                    result.error("MOVE_ERROR", "Failed to move overlay", e.getMessage());
                    return;
                }
            } else if (LockScreenOverlayActivity.isRunning) {
                // LockScreenOverlayActivity doesn't support moving, just return success
                Log.d("OverlayPlugin", "LockScreenOverlayActivity is running, move not supported");
                result.success(true);
            } else {
                Log.w("OverlayPlugin", "No overlay is currently running");
                result.success(false);
            }
        } else if (call.method.equals("getOverlayPosition")) {
            try {
                if (OverlayService.isRunning) {
                    result.success(OverlayService.getCurrentPosition());
                } else if (LockScreenOverlayActivity.isRunning) {
                    // LockScreenOverlayActivity doesn't support position tracking
                    result.success(null);
                } else {
                    result.success(null);
                }
            } catch (Exception e) {
                Log.e("OverlayPlugin", "Failed to get overlay position: " + e.getMessage());
                e.printStackTrace();
                result.error("POSITION_ERROR", "Failed to get overlay position", e.getMessage());
            }
        } else if (call.method.equals("isDeviceLockedOrScreenOff")) {
            try {
                result.success(isDeviceLockedOrScreenOff());
            } catch (Exception e) {
                Log.e("OverlayPlugin", "Failed to check device lock status: " + e.getMessage());
                e.printStackTrace();
                result.error("LOCK_STATUS_ERROR", "Failed to check device lock status", e.getMessage());
            }
        } else if (call.method.equals("openSystemBatterySettings")) {
            result.success(openSystemBatterySettings());
            return;
        } else if (call.method.equals("isSystemBatterySaverOn")) {
            result.success(isSystemBatterySaverOn());
            return;
        } else if (call.method.equals("closeOverlay")) {
           try {
               Log.d("FlutterOverlayWindowPlugin", "🔍 closeOverlay() - Iniciando fechamento");
               Log.d("FlutterOverlayWindowPlugin", "📊 Estado antes - OverlayService: " + OverlayService.isRunning + ", LockScreenOverlay: " + LockScreenOverlayActivity.isRunning);
               
               // Fechar LockScreenOverlayActivity primeiro (se estiver rodando)
               if (LockScreenOverlayActivity.isRunning) {
                    Log.d("FlutterOverlayWindowPlugin", "🛑 Enviando broadcast para fechar LockScreenOverlayActivity");
                    // Envia broadcast para fechar a LockScreenOverlayActivity, caso esteja visível
                    Intent closeIntent = new Intent("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
                    closeIntent.setPackage(context.getPackageName());
                    context.sendBroadcast(closeIntent);
                } else {
                    Log.d("FlutterOverlayWindowPlugin", "ℹ️ LockScreenOverlayActivity não está rodando, pulando broadcast");
                }
               
               OverlayService vivo = OverlayService.instance();
               if (KeepAlive.isEnabled(context) && OverlayService.serviceAlive && vivo != null) {
                    vivo.hideWindow();
                    result.success(true);
                    return;
               }

               // Fechar OverlayService (se estiver rodando)
               if (OverlayService.isRunning || OverlayService.serviceAlive) {
                    Log.d("FlutterOverlayWindowPlugin", "🛑 Parando OverlayService");
                    Intent i = new Intent(context, OverlayService.class);
                    context.stopService(i);
                    
                    // Aguardar o broadcast de destruição do service
                    Log.d("FlutterOverlayWindowPlugin", "⏳ Aguardando confirmação de destruição do OverlayService...");
                    waitForServiceDestruction(result);
                    return; // Retorna aqui, o resultado será enviado no callback
                }
                
                Log.d("FlutterOverlayWindowPlugin", "✅ closeOverlay() concluído com sucesso");
                result.success(true);
            } catch (Exception e) {
                Log.e("OverlayPlugin", "Failed to close overlay: " + e.getMessage());
                e.printStackTrace();
                result.error("CLOSE_ERROR", "Failed to close overlay", e.getMessage());
            }
            return;
        } else {
            result.notImplemented();
        }

    }

    private void setKeepAlive(MethodCall call, Result result) {
        Boolean enabled = call.argument("enabled");
        if (enabled == null || !enabled) {
            KeepAlive.disable(context);
            if (LockScreenOverlayActivity.isRunning) {
                Intent closeIntent = new Intent("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
                closeIntent.setPackage(context.getPackageName());
                context.sendBroadcast(closeIntent);
            }
            if (OverlayService.serviceAlive) {
                context.stopService(new Intent(context, OverlayService.class));
                waitForServiceDestruction(result);
                return;
            }
            result.success(true);
            return;
        }

        if (!checkOverlayPermission()) {
            result.error("PERMISSION", "overlay permission is not enabled", null);
            return;
        }
        String title = call.argument("overlayTitle");
        String content = call.argument("overlayContent");
        Integer width = call.argument("width");
        Integer height = call.argument("height");
        Integer x = call.argument("x");
        Integer y = call.argument("y");
        KeepAlive.enable(context,
                title != null ? title : WindowSetup.overlayTitle,
                content != null ? content : WindowSetup.overlayContent,
                width != null ? width : 50,
                height != null ? height : 50,
                x != null ? x : OverlayConstants.DEFAULT_XY,
                y != null ? y : OverlayConstants.DEFAULT_XY);
        KeepAlive.scheduleWatchdog(context, KeepAlive.WATCHDOG_INTERVAL_MS);

        OverlayService vivo = OverlayService.instance();
        if (OverlayService.serviceAlive && vivo != null) {
            vivo.recreateNotification();
            result.success(true);
            return;
        }
        try {
            Intent intent = new Intent(context, OverlayService.class);
            intent.setAction(KeepAlive.ACTION_KEEP_ALIVE);
            WindowSetup.overlayTitle = KeepAlive.title(context);
            WindowSetup.overlayContent = KeepAlive.content(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            result.success(true);
        } catch (Exception e) {
            Log.e("FlutterOverlayWindowPlugin", "❌ Falha ao iniciar o serviço do manter vivo: " + e.getMessage(), e);
            result.error("SERVICE_ERROR", "Failed to start keep alive service", e.getMessage());
        }
    }

    // Chamado pelo push de "acordar": o servidor viu o entregador online sem
    // localização. Não confere o tempo parado — quem manda é o servidor.
    private void restoreService(MethodCall call, Result result) {
        if (!KeepAlive.isEnabled(context) || !checkOverlayPermission()) {
            result.success(false);
            return;
        }
        OverlayService vivo = OverlayService.instance();
        if (OverlayService.serviceAlive && vivo != null) {
            if (!OverlayService.isRunning && !LockScreenOverlayActivity.isRunning && !KeepAlive.appVisible()) {
                vivo.showWindowFromKeepAlive("restore_service");
            }
            result.success(true);
            return;
        }
        String motivo = call.argument("motivo");
        result.success(KeepAlive.startRestore(context, motivo != null ? motivo : "restore_service"));
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
        }
        if (WindowSetup.messenger != null) {
            WindowSetup.messenger.setMessageHandler(null);
        }
        WindowSetup.clearMessenger();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        mActivity = binding.getActivity();
        binding.addActivityResultListener(this);
        
        // ✅ NÃO criar engine aqui - deixar para o OverlayService
        // O Plugin só deve gerenciar a UI, não criar engines
        Log.d("FlutterOverlayWindowPlugin", "🔌 Plugin anexado à activity - engine será criada pelo OverlayService quando necessário");
    }

    public boolean isDeviceLockedOrScreenOff() {
        try {
            KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

            boolean isLocked = keyguardManager != null && keyguardManager.isKeyguardLocked();
            boolean isScreenOff = powerManager != null && !powerManager.isInteractive();

            return isLocked || isScreenOff;
        } catch (Exception e) {
            Log.e("OverlayPlugin", "Error checking device lock status: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        this.mActivity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        try {
            onAttachedToActivity(binding);
        } catch (Exception e) {
            Log.e("FlutterOverlayWindowPlugin", "Error in onReattachedToActivityForConfigChanges: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDetachedFromActivity() {
        this.mActivity = null;
    }

    @Override
    public void onMessage(@Nullable Object message, @NonNull BasicMessageChannel.Reply reply) {
        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null && engine.getDartExecutor() != null) {
                BasicMessageChannel overlayMessageChannel = new BasicMessageChannel<Object>(
                        engine.getDartExecutor(),
                        OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
                overlayMessageChannel.send(message, reply);
            } else {
                Log.w("FlutterOverlayWindowPlugin", "⚠️ FlutterEngine ou DartExecutor nulo no onMessage");
                reply.reply(null);
            }
        } catch (Exception e) {
            Log.e("FlutterOverlayWindowPlugin", "❌ Error in onMessage: " + e.getMessage(), e);
            reply.reply(null);
        }
    }

    private boolean checkOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(context);
            }
            return true;
        } catch (Exception e) {
            Log.e("OverlayPlugin", "Error checking overlay permission: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Check if the app has the necessary permissions to start foreground service
     */
    private boolean hasForegroundServicePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasBasePermission = context.checkSelfPermission(
                "android.permission.FOREGROUND_SERVICE") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED;

            Log.d("FlutterOverlayWindowPlugin", "🔐 Permission check - FOREGROUND_SERVICE: " + hasBasePermission);
            return hasBasePermission;
        }
        return true; // For older versions, assume permission is granted
    }

    /**
     * Check if the app is currently in the foreground
     */
    private boolean isAppInForeground() {
        try {
            android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
                if (runningProcesses != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                        if (processInfo.processName.equals(context.getPackageName())) {
                            return processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("FlutterOverlayWindowPlugin", "Error checking app foreground state: " + e.getMessage());
        }
        return false;
    }

   @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if (requestCode == REQUEST_CODE_FOR_OVERLAY_PERMISSION) {
                if (pendingResult != null) {
                    pendingResult.success(checkOverlayPermission());
                    pendingResult = null;  // evita chamadas múltiplas
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e("OverlayPlugin", "Error in onActivityResult: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean isLockScreenPermissionGranted() {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            Method checkOpNoThrow = AppOpsManager.class.getDeclaredMethod(
                    "checkOpNoThrow", int.class, int.class, String.class
            );
            checkOpNoThrow.setAccessible(true);

            int uid = Binder.getCallingUid();
            String pkg = context.getPackageName();

            // MIUI - Mostrar sobre a tela de bloqueio
            int OP_SHOW_WHEN_LOCKED = 10020;

            // MIUI - Iniciar Activity em segundo plano
            int OP_START_ACTIVITY_FROM_BACKGROUND = 10021;

            int lockMode = (int) checkOpNoThrow.invoke(appOps, OP_SHOW_WHEN_LOCKED, uid, pkg);
            int bgMode   = (int) checkOpNoThrow.invoke(appOps, OP_START_ACTIVITY_FROM_BACKGROUND, uid, pkg);

            return (lockMode == AppOpsManager.MODE_ALLOWED) &&
                (bgMode == AppOpsManager.MODE_ALLOWED);
        } catch (Exception e) {
            // Se não conseguir verificar, assume permitido
            return true;
        }
    }

    private void openLockScreenPermissionSettings() {
        try {
            Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
            intent.setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
            );
            intent.putExtra("extra_pkgname", context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e1) {
            try {
                // Fallback para MIUI mais antigos
                Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
                intent.setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                );
                intent.putExtra("extra_pkgname", context.getPackageName());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e2) {
                // Fallback Android padrão
                Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                settingsIntent.setData(Uri.fromParts("package", context.getPackageName(), null));
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settingsIntent);
            }
        }
    }
    
    // Um receiver por chamada: com um campo só, a segunda chamada simultânea
    // (fechar a janela e desligar o manter vivo ao mesmo tempo) sobrescrevia a
    // primeira e o Future dela nunca completava.
    private void waitForServiceDestruction(Result result) {
        final boolean[] done = {false};
        final BroadcastReceiver[] receiver = new BroadcastReceiver[1];
        final Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            try {
                context.unregisterReceiver(receiver[0]);
            } catch (Exception e) {
                Log.e("FlutterOverlayWindowPlugin", "Erro ao desregistrar receiver: " + e.getMessage());
            }
            result.success(true);
        };
        receiver[0] = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("FlutterOverlayWindowPlugin", "✅ Broadcast de destruição do OverlayService recebido");
                finish.run();
            }
        };

        IntentFilter filter = new IntentFilter("flutter.overlay.window.OVERLAY_SERVICE_DESTROYED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver[0], filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver[0], filter);
        }

        // Retorna sucesso mesmo com timeout
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!done[0]) {
                Log.w("FlutterOverlayWindowPlugin", "⚠️ Timeout aguardando destruição do OverlayService");
            }
            finish.run();
        }, 5000);
    }

    /**
     * Abre a tela nativa onde o usuário altera o modo de economia de bateria.
     * Primeiro tenta os menus MIUI / HyperOS e depois aplica fallbacks genéricos
     * seguindo o mesmo padrão dos demais métodos de permissões.
     */
    private boolean openSystemBatterySettings() {
        try {
            Intent intent = new Intent("miui.intent.action.POWER_MANAGER");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception miuiPrimary) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettings"
                ));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (Exception miuiFallback) {
                return openBatterySettingsFallbacks();
            }
        }
    }

    private boolean openBatterySettingsFallbacks() {
        List<Intent> intents = new ArrayList<>();

        // MIUI / HyperOS intents extras
        intents.add(componentIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.activity.PowerManagerActivity"));
        intents.add(componentIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"));

        // Samsung OneUI
        intents.add(componentIntent("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"));
        intents.add(componentIntent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"));

        // ColorOS / Realme / Oppo
        intents.add(componentIntent("com.coloros.phonemanager", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"));
        intents.add(componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerSavingModeActivity"));

        // Huawei / Honor
        intents.add(componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"));

        // Stock Android panels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intents.add(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        }
        intents.add(new Intent("android.settings.BATTERY_SETTINGS"));
        intents.add(new Intent("android.intent.action.POWER_USAGE_SUMMARY"));

        for (Intent intent : intents) {
            if (intent == null) continue;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (launchIntent(intent)) {
                return true;
            }
        }

        // Último recurso: abre os detalhes do app
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        details.setData(Uri.fromParts("package", context.getPackageName(), null));
        details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return launchIntent(details);
    }

    /**
     * Safely launches an intent if an activity is available.
     */
    private boolean launchIntent(Intent intent) {
        try {
            if (intent == null) {
                return false;
            }
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.w("FlutterOverlayWindowPlugin", "Unable to launch intent: " + e.getMessage());
        }
        return false;
    }

    /**
     * Utility to create an intent targeting a specific component.
     */
    private Intent componentIntent(String pkg, String cls) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkg, cls));
        return intent;
    }

    /**
     * Checks if the device-wide Battery Saver / Power Save mode is currently enabled.
     * Uses the stock PowerManager API and falls back to Xiaomi / HyperOS specific flags.
     */
    private boolean isSystemBatterySaverOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (powerManager != null && powerManager.isPowerSaveMode()) {
                    return true;
                }
            } catch (Exception e) {
                Log.w("FlutterOverlayWindowPlugin", "Unable to read PowerManager saver flag", e);
            }
        }

        if (isXiaomiBasedRom()) {
            int miuiFlag = readIntSetting("POWER_SAVE_MODE_OPEN");
            if (miuiFlag == 1) {
                return true;
            }
            miuiFlag = readIntSetting("power_save_mode_open");
            if (miuiFlag == 1) {
                return true;
            }
        }

        return false;
    }

    private static volatile boolean miuiProviderAccessible = true;
    private static volatile long lastMiuiProviderFailure = 0L;

    /**
     * Reads Xiaomi / HyperOS specific battery saver state for this package.
     * Returns null when the value cannot be determined so callers can fall back.
     */
    @Nullable
    private Boolean resolveMiuiBatterySaverState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        long now = SystemClock.elapsedRealtime();
        if (!miuiProviderAccessible && (now - lastMiuiProviderFailure) >= MIUI_PROVIDER_RETRY_WINDOW_MS) {
            miuiProviderAccessible = true;
        }
        try {
            AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOpsManager == null) {
                return null;
            }
            int uid = context.getApplicationInfo().uid;
            String pkg = context.getPackageName();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int runAny = appOpsManager.unsafeCheckOpNoThrow(OPSTR_RUN_ANY_IN_BACKGROUND, uid, pkg);
                if (isOpRestricted(runAny)) {
                    return true;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int runInBackground = appOpsManager.unsafeCheckOpNoThrow(OPSTR_RUN_IN_BACKGROUND, uid, pkg);
                if (isOpRestricted(runInBackground)) {
                    return true;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (activityManager != null && activityManager.isBackgroundRestricted()) {
                    return true;
                }
            }

            if (miuiProviderAccessible) {
                Boolean providerState = queryMiuiPowerKeeperProvider();
                if (providerState != null) {
                    return providerState;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                UsageStatsManager usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usageStatsManager != null) {
                    int bucket = usageStatsManager.getAppStandbyBucket();
                    if (bucket == UsageStatsManager.STANDBY_BUCKET_RARE
                            || bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED
                            || bucket == STANDBY_BUCKET_NEVER) {
                        return true;
                    }
                }
            }
        } catch (SecurityException se) {
            Log.w("FlutterOverlayWindowPlugin", "UsageStats permission missing; MIUI saver status unknown");
            return null;
        } catch (Exception e) {
            Log.w("FlutterOverlayWindowPlugin", "Unable to resolve MIUI battery saver flag", e);
            return null;
        }
        return false;
    }

    @Nullable
    private Boolean queryMiuiPowerKeeperProvider() {
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse("content://com.miui.powerkeeper.configure/PowerSaveConfig");
            String pkg = context.getPackageName();
            cursor = context.getContentResolver().query(uri, null, "pkgName=?", new String[]{pkg}, null);
            if ((cursor == null || !cursor.moveToFirst())) {
                if (cursor != null) cursor.close();
                cursor = context.getContentResolver().query(uri, null, "packageName=?", new String[]{pkg}, null);
            }
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            String rawValue = firstNonNull(cursor,
                    "configValue",
                    "value",
                    "intValue",
                    "powerMode",
                    "mode");

            if (rawValue == null) {
                return null;
            }

            String normalized = rawValue.trim().toLowerCase(Locale.US);
            if (normalized.isEmpty()) {
                return null;
            }

            if ("0".equals(normalized)
                    || normalized.contains("normal")
                    || normalized.contains("unrestrict")
                    || normalized.contains("no_limit")
                    || normalized.contains("standard")) {
                return false;
            }

            if ("1".equals(normalized)
                    || "2".equals(normalized)
                    || normalized.contains("save")
                    || normalized.contains("restrict")
                    || normalized.contains("strict")
                    || normalized.contains("limit")) {
                return true;
            }
        } catch (SecurityException se) {
            miuiProviderAccessible = false;
            lastMiuiProviderFailure = SystemClock.elapsedRealtime();
            Log.i("FlutterOverlayWindowPlugin", "MIUI PowerKeeper provider not accessible, falling back to other heuristics");
            return null;
        } catch (Exception e) {
            Log.d("FlutterOverlayWindowPlugin", "Failed querying MIUI PowerKeeper provider", e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    @Nullable
    private String firstNonNull(Cursor cursor, String... columns) {
        for (String column : columns) {
            if (column == null) continue;
            int index = cursor.getColumnIndex(column);
            if (index >= 0) {
                try {
                    String value = cursor.getString(index);
                    if (value != null) {
                        return value;
                    }
                } catch (Exception ignored) {
                    // Ignore invalid column read attempts
                }
            }
        }
        return null;
    }

    private boolean isOpRestricted(int mode) {
        return mode == AppOpsManager.MODE_IGNORED
                || mode == AppOpsManager.MODE_ERRORED;
    }

    private boolean isXiaomiBasedRom() {
        try {
            String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.US) : "";
            String brand = Build.BRAND != null ? Build.BRAND.toLowerCase(Locale.US) : "";
            return manufacturer.contains("xiaomi")
                    || manufacturer.contains("redmi")
                    || manufacturer.contains("poco")
                    || brand.contains("xiaomi")
                    || brand.contains("redmi")
                    || brand.contains("poco");
        } catch (Exception e) {
            return false;
        }
    }

    private int readIntSetting(String key) {
        try {
            return Settings.System.getInt(context.getContentResolver(), key, 0);
        } catch (Exception ignored) {
            return 0;
        }
    }
}

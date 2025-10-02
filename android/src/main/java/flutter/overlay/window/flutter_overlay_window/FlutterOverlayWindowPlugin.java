package flutter.overlay.window.flutter_overlay_window;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.WindowManager;
import android.os.PowerManager;
import android.app.KeyguardManager;
import androidx.core.content.ContextCompat;
import android.os.Handler;
import android.content.BroadcastReceiver;
import android.os.Looper;
import android.content.IntentFilter;
import android.app.AppOpsManager;
import android.os.Binder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationManagerCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
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

    private MethodChannel channel;
    private Context context;
    private Activity mActivity;
    private BasicMessageChannel<Object> messenger;
    private Result pendingResult;
    final int REQUEST_CODE_FOR_OVERLAY_PERMISSION = 1248;
    
    private BroadcastReceiver serviceDestroyedReceiver;
    private Result pendingCloseResult;
    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();

        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.CHANNEL_TAG);
        channel.setMethodCallHandler(this);

        messenger = new BasicMessageChannel<Object>(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.MESSENGER_TAG,
                JSONMessageCodec.INSTANCE);
        messenger.setMessageHandler(this);

        WindowSetup.messenger = messenger;
        if (WindowSetup.messenger != null) {
            WindowSetup.messenger.setMessageHandler(this);
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
                    context.startService(intent);
                    Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 11: startService() executado com sucesso");
                    Log.d("FlutterOverlayWindowPlugin", "✅ OverlayService.startService() chamado com sucesso");
                    
                    // Verificar se o service está rodando após a chamada
                    Log.d("FlutterOverlayWindowPlugin", "📊 Estado após startService - isRunning: " + OverlayService.isRunning);
                } catch (Exception e) {
                    Log.e("FlutterOverlayWindowPlugin", "❌ Falha ao iniciar OverlayService: " + e.getMessage());
                    e.printStackTrace();
                    result.error("SERVICE_ERROR", "Failed to start overlay service", e.getMessage());
                    return;
                }
            }
            Log.d("FlutterOverlayWindowPlugin", "🔍 PONTO 12: showOverlay() concluído com sucesso");
            Log.i("FlutterOverlayWindowPlugin", "✅ showOverlay() - Overlay iniciado com sucesso");
            result.success(null);
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
        } else if (call.method.equals("closeOverlay")) {
           try {
               Log.d("FlutterOverlayWindowPlugin", "🔍 closeOverlay() - Iniciando fechamento");
               Log.d("FlutterOverlayWindowPlugin", "📊 Estado antes - OverlayService: " + OverlayService.isRunning + ", LockScreenOverlay: " + LockScreenOverlayActivity.isRunning);
               
               if (OverlayService.isRunning) {
                    Log.d("FlutterOverlayWindowPlugin", "🛑 Parando OverlayService");
                    Intent i = new Intent(context, OverlayService.class);
                    context.stopService(i);
                    
                    // Aguardar o broadcast de destruição do service
                    Log.d("FlutterOverlayWindowPlugin", "⏳ Aguardando confirmação de destruição do OverlayService...");
                    waitForServiceDestruction(result);
                    return; // Retorna aqui, o resultado será enviado no callback
                }

                if (LockScreenOverlayActivity.isRunning) {
                    Log.d("FlutterOverlayWindowPlugin", "🛑 Enviando broadcast para fechar LockScreenOverlayActivity");
                    // Envia broadcast para fechar a LockScreenOverlayActivity, caso esteja visível
                    Intent closeIntent = new Intent("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
                    closeIntent.setPackage(context.getPackageName());
                    context.sendBroadcast(closeIntent);
                } else {
                    Log.d("FlutterOverlayWindowPlugin", "ℹ️ LockScreenOverlayActivity não está rodando, pulando broadcast");
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

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
        }
        if (WindowSetup.messenger != null) {
            WindowSetup.messenger.setMessageHandler(null);
        }
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        mActivity = binding.getActivity();
        binding.addActivityResultListener(this);
        if (FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG) == null) {
            try {
                Log.i("FlutterOverlayWindowPlugin", "🆕 CRIANDO FLUTTER ENGINE no Plugin");
                long startTime = System.currentTimeMillis();
                
                FlutterEngineGroup enn = new FlutterEngineGroup(context);
                DartExecutor.DartEntrypoint dEntry = new DartExecutor.DartEntrypoint(
                        FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                        "overlayMain");
                FlutterEngine engine = enn.createAndRunEngine(context, dEntry);
                
                long creationTime = System.currentTimeMillis() - startTime;
                Log.i("FlutterOverlayWindowPlugin", "✅ FlutterEngine criada no Plugin em " + creationTime + "ms");
                
                FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
                Log.d("FlutterOverlayWindowPlugin", "💾 Engine armazenada no cache global");
            } catch (Exception e) {
                Log.e("FlutterOverlayWindowPlugin", "❌ Falha ao criar FlutterEngine no Plugin: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            Log.i("FlutterOverlayWindowPlugin", "♻️ REUTILIZANDO FLUTTER ENGINE do cache global");
        }
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
        BasicMessageChannel overlayMessageChannel = new BasicMessageChannel<Object>(
                FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG)
                        .getDartExecutor(),
                OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        overlayMessageChannel.send(message, reply);
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
    
    private void waitForServiceDestruction(Result result) {
        pendingCloseResult = result;
        
        serviceDestroyedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("FlutterOverlayWindowPlugin", "✅ Broadcast de destruição do OverlayService recebido");
                if (pendingCloseResult != null) {
                    pendingCloseResult.success(true);
                    pendingCloseResult = null;
                }
                
                // Desregistrar o receiver
                try {
                    context.unregisterReceiver(serviceDestroyedReceiver);
                } catch (Exception e) {
                    Log.e("FlutterOverlayWindowPlugin", "Erro ao desregistrar receiver: " + e.getMessage());
                }
                serviceDestroyedReceiver = null;
            }
        };
        
        IntentFilter filter = new IntentFilter("flutter.overlay.window.OVERLAY_SERVICE_DESTROYED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(serviceDestroyedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(serviceDestroyedReceiver, filter);
        }
        
        // Timeout de 5 segundos
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (pendingCloseResult != null) {
                Log.w("FlutterOverlayWindowPlugin", "⚠️ Timeout aguardando destruição do OverlayService");
                pendingCloseResult.success(true); // Retorna sucesso mesmo com timeout
                pendingCloseResult = null;
                
                try {
                    context.unregisterReceiver(serviceDestroyedReceiver);
                } catch (Exception e) {
                    Log.e("FlutterOverlayWindowPlugin", "Erro ao desregistrar receiver no timeout: " + e.getMessage());
                }
                serviceDestroyedReceiver = null;
            }
        }, 5000);
    }
}

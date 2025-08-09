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

public class FlutterOverlayWindowPlugin implements
        FlutterPlugin, ActivityAware, BasicMessageChannel.MessageHandler, MethodCallHandler,
        PluginRegistry.ActivityResultListener {

    private MethodChannel channel;
    private Context context;
    private Activity mActivity;
    private BasicMessageChannel<Object> messenger;
    private Result pendingResult;
    final int REQUEST_CODE_FOR_OVERLAY_PERMISSION = 1248;
    private BroadcastReceiver screenUnlockReceiver;
    private final Object lock = new Object();
    private boolean sentResumeForThisUnlock = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();
        registerScreenUnlockReceiver();
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
    private boolean isScreenUnlockReceiverRegistered = false;

    private void registerScreenUnlockReceiver() {
        if (isScreenUnlockReceiverRegistered) {
            Log.d("FlutterOverlayWindowPlugin", "Receiver já registrado, não registra novamente");
            return;
        }
        Log.d("FlutterOverlayWindowPlugin", "Registrando screenUnlockReceiver");

        screenUnlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    synchronized (lock) {
                        if (!sentResumeForThisUnlock) {
                            Log.d("FlutterOverlayWindowPlugin", "Usuário desbloqueou a tela");

                            // Envia broadcast para fechar LockScreenOverlayActivity
                            Intent closeIntent = new Intent("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
                            closeIntent.setPackage(context.getPackageName());
                            context.sendBroadcast(closeIntent);

                            // Envia intent para OverlayService para "resume" do FlutterView
                            Intent resumeIntent = new Intent(context, OverlayService.class);
                            resumeIntent.setAction("RESUME_OVERLAY");
                            context.startService(resumeIntent);

                            Log.d("FlutterOverlayWindowPlugin", "Enviado RESUME_OVERLAY para OverlayService");
                            sentResumeForThisUnlock = true;

                            // Reseta a flag após 3 segundos para permitir novo resume no futuro
                            handler.postDelayed(() -> {
                                synchronized (lock) {
                                    sentResumeForThisUnlock = false;
                                }
                            }, 3000);
                        }
                    }
                }
            }
        };
    IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
    context.registerReceiver(screenUnlockReceiver, filter);
    isScreenUnlockReceiverRegistered = true;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("checkPermission")) {
            result.success(checkOverlayPermission());
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
            if (!checkOverlayPermission()) {
                result.error("PERMISSION", "overlay permission is not enabled", null);
                return;
            }
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

            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

            boolean isScreenOff = powerManager != null && !powerManager.isInteractive();
            boolean isLocked = keyguardManager != null && keyguardManager.isKeyguardLocked();

            boolean lockScreenIntent = false;

            String lockScreenFlag = flag;

            if ("lockScreen".equals(flag) && (isScreenOff || isLocked)) {
                lockScreenIntent = true;
            } else {
                if (flag == null || "lockScreen".equals(flag)) {
                    lockScreenFlag = "flagNotFocusable";
                }
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

            if (lockScreenIntent) {
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
                try {
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
                    context.startService(intent);
                } catch (Exception e) {
                    Log.e("OverlayPlugin", "Failed to start OverlayService: " + e.getMessage());
                    e.printStackTrace();
                    result.error("SERVICE_ERROR", "Failed to start overlay service", e.getMessage());
                    return;
                }
            }
            result.success(null);
        } else if (call.method.equals("isOverlayActive")) {
            result.success(OverlayService.isRunning);
            return;
        } else if (call.method.equals("moveOverlay")) {

            if (LockScreenOverlayActivity.isRunning) {
                // Envia broadcast para fechar a LockScreenOverlayActivity, caso esteja visível
                Intent closeIntent = new Intent("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
                closeIntent.setPackage(context.getPackageName());
                context.sendBroadcast(closeIntent);
            }
            if (OverlayService.isRunning) {
                try {
                    int x = call.argument("x");
                    int y = call.argument("y");
                    OverlayService.moveOverlay(x, y);
                } catch (Exception e) {
                    Log.e("OverlayPlugin", "Failed to move overlay: " + e.getMessage());
                    e.printStackTrace();
                    result.error("MOVE_ERROR", "Failed to move overlay", e.getMessage());
                    return;
                }
            }
            result.success(true);
        } else if (call.method.equals("getOverlayPosition")) {
            try {
                result.success(OverlayService.getCurrentPosition());
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
               if (OverlayService.isRunning) {
                    Intent i = new Intent(context, OverlayService.class);
                    context.stopService(i);
                }

                if (LockScreenOverlayActivity.isRunning) {
                    // Envia broadcast para fechar a LockScreenOverlayActivity, caso esteja visível
                    Intent closeIntent = new Intent("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
                    closeIntent.setPackage(context.getPackageName());
                    context.sendBroadcast(closeIntent);
                }
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
        if (screenUnlockReceiver != null && isScreenUnlockReceiverRegistered) {
            context.unregisterReceiver(screenUnlockReceiver);
            screenUnlockReceiver = null;
            isScreenUnlockReceiverRegistered = false;
        }
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        mActivity = binding.getActivity();
        binding.addActivityResultListener(this);
        if (FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG) == null) {
            try {
                FlutterEngineGroup enn = new FlutterEngineGroup(context);
                DartExecutor.DartEntrypoint dEntry = new DartExecutor.DartEntrypoint(
                        FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                        "overlayMain");
                FlutterEngine engine = enn.createAndRunEngine(context, dEntry);
                FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
            } catch (Exception e) {
                Log.e("FlutterOverlayWindowPlugin", "Failed to create Flutter engine in onAttachedToActivity: " + e.getMessage());
                e.printStackTrace();
            }
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
}

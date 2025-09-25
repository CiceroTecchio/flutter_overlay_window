package flutter.overlay.window.flutter_overlay_window;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Insets;
import android.view.WindowMetrics;
import android.view.WindowInsets;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

import java.util.concurrent.atomic.AtomicBoolean;

public class OverlayService extends Service implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    public static OverlayService instance;
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    // Removed mAnimationHandler - using direct execution
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;
    private boolean userPresent = false;
    
    // Flag to track if foreground service was successfully started
    private boolean isForegroundService = false;

    // Critical safety flags to prevent segfaults
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean isEngineValid = new AtomicBoolean(false);
    private final AtomicBoolean isSurfaceValid = new AtomicBoolean(false);
    private final Object engineLock = new Object();
    private final Object surfaceLock = new Object();
    
    // Engine isolation flags to prevent Dart VM crashes
    private final AtomicBoolean isEngineIsolated = new AtomicBoolean(false);
    private final AtomicBoolean isEnginePaused = new AtomicBoolean(false);
    private volatile boolean isEngineDestroyed = false;

    private BroadcastReceiver screenUnlockReceiver;
    private boolean isReceiverRegistered = false;
    private final Object lock = new Object();
    // Removed handler - using direct execution
    private boolean sentResumeForThisUnlock = false;

    private BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // Safe engine access with validation
                if (!isEngineValid.get() || isDestroyed.get()) {
                    Log.w("OverlayService", "Engine not valid or service destroyed, skipping resume");
                    return;
                }
                
                try {
                    FlutterEngine flutterEngine = getValidEngine();
                    if (flutterEngine != null && isEngineValid(flutterEngine)) {
                        // Direct execution without delay for better performance
                        try {
                            if (flutterEngine.getLifecycleChannel() != null) {
                                flutterEngine.getLifecycleChannel().appIsResumed();
                                Log.d("OverlayService", "Engine lifecycle resumed after screen unlock");
                            }
                        } catch (Exception e) {
                            Log.e("OverlayService", "Error resuming engine: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    Log.e("OverlayService", "Error resuming engine: " + e.getMessage());
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private BroadcastReceiver configurationChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                Log.d("OverlayService", "Configuration change detected, updating overlay");
                // Direct execution for better performance
                if (isRunning && windowManager != null && flutterView != null) {
                    try {
                        // Update window metrics for new configuration
                        updateWindowMetrics();
                        
                        // Use safe surface operation for layout updates
                        safeSurfaceOperation(() -> {
                            flutterView.requestLayout();
                            Log.d("OverlayService", "Layout requested after configuration change");
                        }, "requestLayout after config change");
                        
                    } catch (Exception e) {
                        Log.e("OverlayService", "Error handling configuration change: " + e.getMessage());
                    }
                }
            }
        }
    };

    private BroadcastReceiver appLifecycleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.d("OverlayService", "Screen off detected, but keeping overlay functional");
                // Don't isolate engine for overlay - it needs to stay active
                handleAppLifecycleChange("paused");
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                Log.d("OverlayService", "Screen on detected, overlay should remain active");
                handleAppLifecycleChange("resumed");
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                Log.d("OverlayService", "User present detected, overlay should remain active");
                handleAppLifecycleChange("resumed");
            }
        }
    };

    private void updateWindowMetrics() {
        if (windowManager == null) return;
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
                Insets insets = metrics.getWindowInsets()
                        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                int w = metrics.getBounds().width() - insets.left - insets.right;
                int h = metrics.getBounds().height() - insets.top - insets.bottom;
                szWindow.set(w, h);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                windowManager.getDefaultDisplay().getSize(szWindow);
            } else {
                DisplayMetrics displaymetrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getMetrics(displaymetrics);
                szWindow.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error updating window metrics: " + e.getMessage());
        }
    }

    private void registerScreenUnlockReceiver() {
        if (isReceiverRegistered) return;

        screenUnlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    synchronized (lock) {
                        if (!sentResumeForThisUnlock) {
                            Log.d("OverlayService", "Usuário desbloqueou a tela");

                            // Fecha LockScreenOverlayActivity (se estiver aberta)
                            Intent closeIntent = new Intent("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
                            closeIntent.setPackage(context.getPackageName());
                            context.sendBroadcast(closeIntent);

                            // Safe engine access
                            FlutterEngine flutterEngine = getValidEngine();
                            if (flutterView != null && flutterEngine != null) {
                                try {
                                    flutterView.attachToFlutterEngine(flutterEngine);
                                    flutterEngine.getLifecycleChannel().appIsResumed();
                                    flutterView.invalidate();
                                    flutterView.requestLayout();
                                    bringOverlayToFront();
                                    Log.d("OverlayService", "FlutterView resumido e redraw feito.");
                                } catch (Exception e) {
                                    Log.e("OverlayService", "Error resuming FlutterView: " + e.getMessage());
                                }
                            } else {
                                Log.w("OverlayService", "flutterView ou flutterEngine nulos ao tentar resumir.");
                            }

                            sentResumeForThisUnlock = true;

                            // Direct execution without delay for better performance
                            new Thread(() -> {
                                try {
                                    Thread.sleep(3000);
                                    synchronized (lock) {
                                        sentResumeForThisUnlock = false;
                                    }
                                } catch (InterruptedException e) {
                                    Log.e("OverlayService", "Thread interrupted: " + e.getMessage());
                                }
                            }).start();
                        }
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenUnlockReceiver, filter);
        isReceiverRegistered = true;
    }

    /**
     * Safe engine validation to prevent segfaults
     */
    private boolean isEngineValid(FlutterEngine engine) {
        if (engine == null) {
            Log.w("OverlayService", "Engine is null");
            return false;
        }
        
        try {
            // Check if engine is not destroyed
            if (engine.getDartExecutor() == null) {
                Log.w("OverlayService", "Engine DartExecutor is null");
                return false;
            }
            
            // Check if DartExecutor is executing (this means engine is busy)
            // But allow it for overlay use as it might be normal
            if (engine.getDartExecutor().isExecutingDart()) {
                Log.w("OverlayService", "Engine DartExecutor is executing Dart code - allowing for overlay use");
                // Don't return false here, allow the engine to be used
            }
            
            // Check if lifecycle channel is available
            if (engine.getLifecycleChannel() == null) {
                Log.w("OverlayService", "Engine LifecycleChannel is null");
                return false;
            }
            
            // Additional check: try to access renderer
            try {
                if (engine.getRenderer() == null) {
                    Log.w("OverlayService", "Engine Renderer is null");
                    return false;
                }
            } catch (Exception e) {
                Log.w("OverlayService", "Engine Renderer not accessible: " + e.getMessage());
                return false;
            }
            
            Log.d("OverlayService", "Engine validation passed");
            return true;
        } catch (Exception e) {
            Log.e("OverlayService", "Error validating engine: " + e.getMessage());
            return false;
        }
    }

    /**
     * Safe engine access with validation
     */
    private FlutterEngine getValidEngine() {
        if (isDestroyed.get() || isEngineDestroyed) {
            Log.w("OverlayService", "Service destroyed or engine destroyed, cannot access engine");
            return null;
        }
        
        synchronized (engineLock) {
            try {
                // Allow access to isolated engine for overlay operations
                // Overlay needs to work even when screen is off
                if (isEngineIsolated.get()) {
                    Log.d("OverlayService", "Engine is isolated, but allowing access for overlay operations");
                }
                
                // Try to get engine from cache
                FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
                if (engine != null) {
                    // Check if engine is valid, but be more lenient with executing Dart
                    if (isEngineValid(engine)) {
                        Log.d("OverlayService", "Found valid engine in cache");
                        return engine;
                    } else {
                        Log.w("OverlayService", "Cached engine not valid, but trying to use it anyway");
                        // Try to use the engine even if validation failed
                        if (engine.getDartExecutor() != null && engine.getLifecycleChannel() != null) {
                            Log.d("OverlayService", "Using cached engine despite validation issues");
                            return engine;
                        }
                    }
                }
                
                // Try to get default engine as fallback
                Log.w("OverlayService", "Overlay engine not found, trying default engine");
                FlutterEngine defaultEngine = FlutterEngineCache.getInstance().get("default");
                if (defaultEngine != null) {
                    if (isEngineValid(defaultEngine)) {
                        Log.d("OverlayService", "Using default engine as fallback");
                        // Cache the default engine for overlay use
                        FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, defaultEngine);
                        return defaultEngine;
                    } else {
                        Log.w("OverlayService", "Default engine not valid, but trying to use it anyway");
                        // Try to use the default engine even if validation failed
                        if (defaultEngine.getDartExecutor() != null && defaultEngine.getLifecycleChannel() != null) {
                            Log.d("OverlayService", "Using default engine despite validation issues");
                            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, defaultEngine);
                            return defaultEngine;
                        }
                    }
                }
                
                Log.w("OverlayService", "No valid engine found in cache");
                return null;
            } catch (Exception e) {
                Log.e("OverlayService", "Error accessing engine: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Safely validates and manages Flutter surface state
     */
    private boolean isSurfaceSafe() {
        synchronized (surfaceLock) {
            if (isDestroyed.get() || !isEngineValid.get()) {
                return false;
            }
            
            if (flutterView == null || windowManager == null) {
                return false;
            }
            
            try {
                // For overlay creation, we don't need to check if FlutterView is attached to parent
                // because we're about to attach it to WindowManager
                // Just check if the view exists and is valid
                if (flutterView.getWidth() <= 0 || flutterView.getHeight() <= 0) {
                    Log.w("OverlayService", "FlutterView has invalid dimensions: " + 
                          flutterView.getWidth() + "x" + flutterView.getHeight());
                    return false;
                }
                
                return true;
            } catch (Exception e) {
                Log.e("OverlayService", "Error checking surface safety: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Safely performs surface operations with validation
     */
    private void safeSurfaceOperation(Runnable operation, String operationName) {
        if (!isSurfaceSafe()) {
            Log.w("OverlayService", "Surface not safe for operation: " + operationName);
            return;
        }
        
        try {
            operation.run();
        } catch (Exception e) {
            Log.e("OverlayService", "Error in surface operation " + operationName + ": " + e.getMessage());
            // Mark surface as invalid if operation fails
            isSurfaceValid.set(false);
        }
    }

    /**
     * Attempts to recreate the FlutterEngine if it's lost or invalid
     */
    private boolean recreateEngineIfNeeded() {
        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine == null || !isEngineValid(engine)) {
                Log.w("OverlayService", "Engine lost or invalid, attempting to recreate");
                
                // Create new engine group
                FlutterEngineGroup engineGroup = new FlutterEngineGroup(this);
                Log.d("OverlayService", "Created new engine group");
                
                // Create entry point
                DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                        FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                        "overlayMain");

                // Create and run engine
                FlutterEngine newEngine = engineGroup.createAndRunEngine(this, entryPoint);
                
                // Wait a bit for engine to initialize
                Thread.sleep(100);
                
                // Cache the engine
                FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, newEngine);
                
                // Mark as valid
                isEngineValid.set(true);
                
                Log.d("OverlayService", "New FlutterEngine created and cached successfully");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e("OverlayService", "Failed to recreate engine: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Safely handles semantics updates to prevent crashes
     */
    private void handleSemanticsUpdate() {
        try {
            if (flutterView != null && isSurfaceSafe()) {
                // Disable semantics updates for overlay windows to prevent crashes
                flutterView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                
                // Clear any pending semantics updates
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    flutterView.setAccessibilityDelegate(null);
                }
                
                Log.d("OverlayService", "Semantics handling configured for overlay");
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error handling semantics update: " + e.getMessage());
        }
    }

    /**
     * Safely handles app lifecycle changes to prevent crashes during background/foreground transitions
     */
    private void handleAppLifecycleChange(String state) {
        try {
            if (isDestroyed.get()) {
                Log.w("OverlayService", "Service destroyed, skipping lifecycle change: " + state);
                return;
            }

            FlutterEngine engine = getValidEngine();
            if (engine == null) {
                Log.w("OverlayService", "Engine not available for lifecycle change: " + state);
                return;
            }

            // For overlay, we don't need to change engine lifecycle states
            // Overlay should remain functional regardless of app state
            Log.d("OverlayService", "Overlay lifecycle change: " + state + " - keeping overlay active");

        } catch (Exception e) {
            Log.e("OverlayService", "Error in handleAppLifecycleChange: " + e.getMessage());
        }
    }

    /**
     * Safely isolates the Flutter engine to prevent Dart VM crashes
     */
    private void isolateEngine() {
        logOperationStart("isolateEngine");
        
        try {
            if (isEngineDestroyed || isDestroyed.get()) {
                Log.w("OverlayService", "Engine already destroyed or service destroyed, skipping isolation");
                return;
            }

            synchronized (engineLock) {
                if (isEngineIsolated.get()) {
                    Log.d("OverlayService", "Engine already isolated");
                    return;
                }

                FlutterEngine engine = getValidEngine();
                if (engine == null) {
                    Log.w("OverlayService", "No valid engine to isolate");
                    return;
                }

                // Pause the engine to prevent Dart VM operations
                try {
                    if (engine.getLifecycleChannel() != null) {
                        engine.getLifecycleChannel().appIsDetached();
                        Log.d("OverlayService", "Engine detached for isolation");
                    }
                } catch (Exception e) {
                    Log.w("OverlayService", "Error detaching engine for isolation: " + e.getMessage());
                    logOperationFailure("engine_detach", e);
                }

                // Mark engine as isolated
                isEngineIsolated.set(true);
                isEnginePaused.set(true);
                
                Log.d("OverlayService", "Engine isolated successfully");
                logOperationComplete("isolateEngine");
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error isolating engine: " + e.getMessage());
            logOperationFailure("isolateEngine", e);
        }
    }

    /**
     * Safely restores the Flutter engine from isolation
     */
    private void restoreEngine() {
        logOperationStart("restoreEngine");
        
        try {
            if (isDestroyed.get()) {
                Log.w("OverlayService", "Service destroyed, cannot restore engine");
                return;
            }

            synchronized (engineLock) {
                if (!isEngineIsolated.get()) {
                    Log.d("OverlayService", "Engine not isolated, no need to restore");
                    return;
                }

                FlutterEngine engine = getValidEngine();
                if (engine == null) {
                    Log.w("OverlayService", "No valid engine to restore");
                    return;
                }

                // Restore engine operations
                try {
                    if (engine.getLifecycleChannel() != null) {
                        engine.getLifecycleChannel().appIsResumed();
                        Log.d("OverlayService", "Engine restored and resumed");
                    }
                } catch (Exception e) {
                    Log.w("OverlayService", "Error resuming engine after restore: " + e.getMessage());
                    logOperationFailure("engine_resume", e);
                }

                // Mark engine as restored
                isEngineIsolated.set(false);
                isEnginePaused.set(false);
                
                Log.d("OverlayService", "Engine restored successfully");
                logOperationComplete("restoreEngine");
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error restoring engine: " + e.getMessage());
            logOperationFailure("restoreEngine", e);
        }
    }


    /**
     * Gets current service state for debugging
     */
    private String getServiceState() {
        StringBuilder state = new StringBuilder();
        state.append("running:").append(isRunning);
        state.append(",destroyed:").append(isDestroyed.get());
        state.append(",engineValid:").append(isEngineValid.get());
        state.append(",surfaceValid:").append(isSurfaceValid.get());
        state.append(",engineIsolated:").append(isEngineIsolated.get());
        state.append(",enginePaused:").append(isEnginePaused.get());
        state.append(",engineDestroyed:").append(isEngineDestroyed);
        return state.toString();
    }

    /**
     * Logs overlay operation start (no Sentry, just local logging)
     */
    private void logOperationStart(String operation) {
        Log.d("OverlayService", "Starting overlay operation: " + operation);
    }

    /**
     * Logs overlay operation completion (no Sentry, just local logging)
     */
    private void logOperationComplete(String operation) {
        Log.d("OverlayService", "Completed overlay operation: " + operation);
    }

    /**
     * Logs overlay operation failure (local logging only)
     */
    private void logOperationFailure(String operation, Exception exception) {
        Log.e("OverlayService", "Failed overlay operation: " + operation + " - " + exception.getMessage());
        Log.e("OverlayService", "Service state: " + getServiceState());
        exception.printStackTrace();
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        logOperationStart("onDestroy");
        
        // Mark service as destroyed to prevent further operations
        isDestroyed.set(true);
        isEngineValid.set(false);
        isSurfaceValid.set(false);
        isEngineDestroyed = true;
        
        // Isolate engine before destruction to prevent Dart VM crashes
        isolateEngine();

        try {
            // Safe engine access with additional validation
            FlutterEngine engine = getValidEngine();
            if (engine != null && isEngineValid(engine)) {
                Log.d("OverLay", "Parando som do ringtone");
                try {
                    // Double-check engine is still valid before calling methods
                    if (engine.getDartExecutor() != null && !engine.getDartExecutor().isExecutingDart()) {
                        new MethodChannel(engine.getDartExecutor(), "my_custom_overlay_channel")
                                .invokeMethod("onOverlayClosed", null);
                        Log.d("OverLay", "onOverlayClosed chamado com sucesso");
                    } else {
                        Log.w("OverLay", "Engine DartExecutor não está disponível para chamar onOverlayClosed");
                    }
                } catch (Exception e) {
                    Log.e("OverLay", "Error calling onOverlayClosed: " + e.getMessage());
                }
            } else {
                Log.d("OverLay", "FlutterEngine nulo ou inválido, não foi possível chamar onOverlayClosed");
            }
        } catch (Exception e) {
            Log.e("OverLay", "Falha ao parar som do ringtone", e);
        }

        if (windowManager != null && flutterView != null) {
            try {
                // Safe detachment with validation
                if (flutterView.getParent() != null) {
                    windowManager.removeView(flutterView);
                    Log.d("OverLay", "FlutterView removido do WindowManager");
                } else {
                    Log.w("OverLay", "FlutterView já foi removido do WindowManager");
                }
            } catch (Exception e) {
                Log.e("OverLay", "Erro ao remover flutterView", e);
            }
            
            try {
                // Safe engine detachment
                FlutterEngine engine = getValidEngine();
                if (engine != null && isEngineValid(engine)) {
                    flutterView.detachFromFlutterEngine();
                    Log.d("OverLay", "FlutterView desanexado do engine com sucesso");
                } else {
                    Log.w("OverLay", "Engine inválido, pulando detachFromFlutterEngine");
                }
            } catch (Exception e) {
                Log.e("OverLay", "Erro ao desanexar FlutterView do engine", e);
            }
            
            flutterView = null;
            windowManager = null;
        }

        isRunning = false;
        instance = null;

        try {
            NotificationManager notificationManager = (NotificationManager)
                    getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
            }
        } catch (Exception e) {
            Log.e("OverLay", "Erro ao cancelar notificação", e);
        }

        super.onDestroy();
        
        // Unregister receivers safely
        try {
            if (isReceiverRegistered && screenUnlockReceiver != null) {
                unregisterReceiver(screenUnlockReceiver);
                isReceiverRegistered = false;
            }
        } catch (Exception e) {
            Log.e("OverLay", "Error unregistering screenUnlockReceiver", e);
        }
        
        try {
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver);
            }
        } catch (Exception e) {
            Log.e("OverLay", "Error unregistering screenReceiver", e);
        }
        
        try {
            if (configurationChangeReceiver != null) {
                unregisterReceiver(configurationChangeReceiver);
            }
        } catch (Exception e) {
            Log.e("OverLay", "Error unregistering configurationChangeReceiver", e);
        }
        
        try {
            if (appLifecycleReceiver != null) {
                unregisterReceiver(appLifecycleReceiver);
            }
        } catch (Exception e) {
            Log.e("OverLay", "Error unregistering appLifecycleReceiver", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        try {
            Log.d("OverlayService", "onStartCommand called with intent: " + (intent != null ? intent.getAction() : "null"));
            
            if (intent == null) {
                Log.w("OverlayService", "Intent is null, returning START_NOT_STICKY");
                return START_NOT_STICKY;
            }
            String action = intent.getAction();

        // 🔹 Se overlay já ativo e ação for apenas trazer para frente
        if (windowManager != null && flutterView != null && "SHOW_OVERLAY_AGAIN".equals(action)) {
            int width = intent.getIntExtra("width", 300);
            int height = intent.getIntExtra("height", 300);
            boolean enableDrag = intent.getBooleanExtra("enableDrag", false);
            resizeOverlay(width, height, enableDrag, null);

            int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
            int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
            
            // Convert to pixels for proper positioning
            int newX = startX == OverlayConstants.DEFAULT_XY ? 0 : dpToPx(startX);
            int newY = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : dpToPx(startY);
            
            // Update gravity for absolute positioning if specific coordinates are provided AND no alignment override
            boolean hasSpecificPosition = (startX != OverlayConstants.DEFAULT_XY || startY != OverlayConstants.DEFAULT_XY);
            boolean hasAlignmentOverride = (WindowSetup.gravity != Gravity.CENTER);
            if (hasSpecificPosition && !hasAlignmentOverride && windowManager != null && flutterView != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                params.gravity = Gravity.TOP | Gravity.LEFT;
                windowManager.updateViewLayout(flutterView, params);
                Log.d("OverlayService", "Updated gravity to TOP|LEFT for absolute positioning");
            } else if (hasAlignmentOverride && windowManager != null && flutterView != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                params.gravity = WindowSetup.gravity;
                windowManager.updateViewLayout(flutterView, params);
                Log.d("OverlayService", "Updated gravity to alignment: " + WindowSetup.gravity);
            }
            
            moveOverlayInternal(newX, newY, null);
            bringOverlayToFront();
            Log.d("OverlayService", "Overlay já ativo, reposicionado para: " + newX + "," + newY);
            return START_STICKY;
        }

        mResources = getApplicationContext().getResources();

        // Try to start as foreground service if not already started
        // Force start when called from onStartCommand to avoid timeout
        if (!isForegroundService) {
            tryStartForegroundService(true);
        }

        initOverlay(intent);

        return START_STICKY;
        } catch (Exception e) {
            Log.e("OverlayService", "Error in onStartCommand: " + e.getMessage());
            e.printStackTrace();
            isRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void initOverlay(Intent intent) {
        logOperationStart("initOverlay");
        
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        
                // Log intent details locally
        Log.d("OverlayService", "Initializing overlay with startX: " + startX + ", startY: " + startY + ", isCloseWindow: " + isCloseWindow);

        if (isCloseWindow) {
            if (windowManager != null) {
                    windowManager.removeView(flutterView);
                    flutterView.detachFromFlutterEngine();
                    windowManager = null;
                    stopSelf();
            }
            isRunning = false;
            return;
        }

        if (windowManager != null && flutterView != null) {
                windowManager.removeView(flutterView);
                flutterView.detachFromFlutterEngine();
                windowManager = null;
                stopSelf();
        }

        isRunning = true;
        Log.d("onStartCommand", "Service started");

        // Safe engine access with validation and recreation
        FlutterEngine engine = getValidEngine();
        if (engine == null) {
            Log.w("OverlayService", "FlutterEngine não encontrado no cache ou inválido, tentando recriar");
            if (recreateEngineIfNeeded()) {
                engine = getValidEngine();
                if (engine == null) {
                    Log.e("OverlayService", "Falha ao recriar FlutterEngine");
                    isRunning = false;
                    stopSelf();
                    return;
                }
            } else {
                Log.e("OverlayService", "Não foi possível recriar FlutterEngine");
                isRunning = false;
                stopSelf();
                return;
            }
        }
        
        // Mark engine as valid
        isEngineValid.set(true);
        if (flutterChannel == null) {
            flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
            flutterChannel.setMethodCallHandler((call, result) -> {
                switch (call.method) {
                    case "updateFlag":
                        String flag = call.argument("flag");
                        updateOverlayFlag(result, flag);
                        break;
                    case "updateOverlayPosition":
                        int x = call.argument("x");
                        int y = call.argument("y");
                        moveOverlayInternal(x, y, result);
                        break;
                    case "resizeOverlay":
                        int width = call.argument("width");
                        int height = call.argument("height");
                        boolean enableDrag = call.argument("enableDrag");
                        resizeOverlay(width, height, enableDrag, result);
                        break;
                    default:
                        result.notImplemented();
                }
            });
        }

                if (overlayMessageChannel == null) {
            overlayMessageChannel = new BasicMessageChannel<>(engine.getDartExecutor(),
                    OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        }
        overlayMessageChannel.setMessageHandler((message, reply) -> WindowSetup.messenger.send(message));

        try {
            if (flutterView != null) {
                flutterView.detachFromFlutterEngine();
                if (windowManager != null) {
                    windowManager.removeView(flutterView);
                    windowManager = null;
                }
            }

            // Simple engine validation - just ensure the engine exists
            if (engine == null) {
                Log.e("OverlayService", "FlutterEngine is null, cannot create overlay");
                return;
            }
            
            // Log engine state for debugging but don't block
            try {
                if (engine.getRenderer() != null) {
                    Log.d("OverlayService", "Engine renderer is available");
                } else {
                    Log.d("OverlayService", "Engine renderer is null, but proceeding");
                }
            } catch (Exception e) {
                Log.d("OverlayService", "Could not check engine state: " + e.getMessage());
            }

            // Safe lifecycle channel access
            try {
                if (engine.getLifecycleChannel() != null) {
                    engine.getLifecycleChannel().appIsResumed();
                    Log.d("OverlayService", "Engine lifecycle resumed successfully");
                } else {
                    Log.w("OverlayService", "Engine lifecycle channel is null");
                }
            } catch (Exception e) {
                Log.e("OverlayService", "Error resuming engine lifecycle: " + e.getMessage());
            }
            
            // Disable semantics at engine level to prevent crashes
            try {
                if (engine.getAccessibilityChannel() != null) {
                    // Disable accessibility features at engine level
                    Log.d("OverlayService", "Accessibility channel found, disabling semantics");
                }
            } catch (Exception e) {
                Log.d("OverlayService", "Accessibility channel not available or already disabled");
            }
            
            // Create FlutterView with proper error handling
            try {
                logOperationStart("createFlutterView");
                
                // Disable Impeller renderer to prevent surface crashes
                // This is a safety measure for overlay windows
                System.setProperty("flutter.impeller.enabled", "false");
                System.setProperty("flutter.enable-impeller", "false");
                
                // Disable semantics to prevent crashes in overlay windows
                System.setProperty("flutter.accessibility", "false");
                System.setProperty("flutter.semantics", "false");
                
                Log.d("OverlayService", "FlutterView creation started with safety properties set");
                
                // Create FlutterTextureView with additional safety
                FlutterTextureView textureView = new FlutterTextureView(getApplicationContext());
                flutterView = new FlutterView(getApplicationContext(), textureView);
                
                // Add surface error listener to catch surface-related crashes
                flutterView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        Log.d("OverlayService", "FlutterView attached to window");
                        isSurfaceValid.set(true);
                        // Ensure engine is still valid when view attaches
                        FlutterEngine engine = getValidEngine();
                        if (engine != null && isEngineValid(engine)) {
                            try {
                                flutterView.attachToFlutterEngine(engine);
                                Log.d("OverlayService", "FlutterView reattached to engine after window attach");
                            } catch (Exception e) {
                                Log.e("OverlayService", "Error reattaching FlutterView to engine: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        Log.d("OverlayService", "FlutterView detached from window");
                        isSurfaceValid.set(false);
                        // Don't detach from engine here as it might be temporary
                        // The engine detachment should only happen in onDestroy
                    }
                });
                
                // Add layout listener to monitor surface changes
                flutterView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom, 
                                             int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        int width = right - left;
                        int height = bottom - top;
                        Log.d("OverlayService", "FlutterView layout changed: " + width + "x" + height);
                        
                        // Validate surface after layout change
                        if (width > 0 && height > 0) {
                            isSurfaceValid.set(true);
                        } else {
                            Log.w("OverlayService", "Invalid surface dimensions after layout change");
                            isSurfaceValid.set(false);
                        }
                    }
                });
                
                // Safe engine attachment with validation
                try {
                    if (engine != null && isEngineValid(engine)) {
                        flutterView.attachToFlutterEngine(engine);
                        Log.d("OverlayService", "FlutterView attached to engine successfully");
                    } else {
                        Log.e("OverlayService", "Cannot attach FlutterView - engine is null or invalid");
                        throw new IllegalStateException("Engine is null or invalid");
                    }
                } catch (Exception e) {
                    Log.e("OverlayService", "Error attaching FlutterView to engine: " + e.getMessage());
                    throw e;
                }
                
                flutterView.setFitsSystemWindows(true);
                flutterView.setFocusable(true);
                flutterView.setFocusableInTouchMode(true);
                flutterView.setBackgroundColor(Color.TRANSPARENT);
                flutterView.setOnTouchListener(this);
                
                // Disable accessibility features to prevent semantics crashes
                try {
                    flutterView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    flutterView.setAccessibilityDelegate(null);
                    Log.d("OverlayService", "Accessibility features disabled for overlay");
                } catch (Exception e) {
                    Log.w("OverlayService", "Could not disable accessibility features: " + e.getMessage());
                }
                
                // Mark surface as valid after successful creation
                isSurfaceValid.set(true);
                
                // Handle semantics to prevent crashes
                handleSemanticsUpdate();
                
                logOperationComplete("createFlutterView");
                Log.d("OverlayService", "FlutterView created successfully with safety measures");
            } catch (Exception e) {
                Log.e("OverlayService", "Failed to create FlutterView: " + e.getMessage());
                e.printStackTrace();
                isSurfaceValid.set(false);
                isRunning = false;
                stopSelf();
                return;
            }

            // Define tamanho da tela
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
                    Insets insets = metrics.getWindowInsets()
                            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                    int w = metrics.getBounds().width() - insets.left - insets.right;
                    int h = metrics.getBounds().height() - insets.top - insets.bottom;
                    szWindow.set(w, h);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    windowManager.getDefaultDisplay().getSize(szWindow);
                } else {
                    DisplayMetrics displaymetrics = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getMetrics(displaymetrics);
                    szWindow.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
                }
            } catch (Exception e) {
                Log.e("OverlayService", "Error getting window metrics in initOverlay: " + e.getMessage());
                // Fallback to default display
                try {
                    DisplayMetrics displaymetrics = new DisplayMetrics();
                    windowManager.getDefaultDisplay().getMetrics(displaymetrics);
                    szWindow.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
                } catch (Exception ex) {
                    Log.e("OverlayService", "Critical error getting display metrics: " + ex.getMessage());
                    // Set default values if all else fails
                    szWindow.set(1080, 1920);
                }
            }

            int width = intent.getIntExtra("width", WindowSetup.width);
            int height = intent.getIntExtra("height", screenHeight());
            
            // Calculate initial position properly
            int initialX = startX == OverlayConstants.DEFAULT_XY ? 0 : dpToPx(startX);
            int initialY = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : dpToPx(startY);
            
            int layoutWidth = (width == -1999 || width == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(width);
            int layoutHeight = (height == -1999 || height == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(height);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    layoutWidth,
                    layoutHeight,
                    initialX,  // Set initial X position
                    initialY,  // Set initial Y position
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowSetup.flag
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            }
            
            // Determine gravity based on whether we have specific positions AND no alignment override
            boolean hasSpecificPosition = (startX != OverlayConstants.DEFAULT_XY || startY != OverlayConstants.DEFAULT_XY);
            boolean hasAlignmentOverride = (WindowSetup.gravity != Gravity.CENTER);
            
            if (hasSpecificPosition && !hasAlignmentOverride) {
                // Use TOP|LEFT gravity for absolute positioning when specific coordinates are provided
                // and no alignment is specified (alignment would override this)
                params.gravity = Gravity.TOP | Gravity.LEFT;
                Log.d("OverlayService", "Using absolute positioning with TOP|LEFT gravity");
            } else {
                // Use WindowSetup gravity for alignment-based positioning
                params.gravity = WindowSetup.gravity;
                Log.d("OverlayService", "Using WindowSetup gravity: " + WindowSetup.gravity + " (hasSpecificPosition: " + hasSpecificPosition + ", hasAlignmentOverride: " + hasAlignmentOverride + ")");
            }

            // Add view with proper error handling
            try {
                // Direct execution for better performance
                try {
                    if (windowManager != null && flutterView != null && isRunning) {
                        // Check if FlutterView is not already attached to WindowManager
                        if (flutterView.getParent() == null) {
                            windowManager.addView(flutterView, params);
                            Log.d("OverlayService", "Overlay view added successfully at position: " + initialX + "," + initialY + " with gravity: " + params.gravity);
                            
                            // Re-apply semantics handling after view is added
                            handleSemanticsUpdate();
                        } else {
                            Log.w("OverlayService", "FlutterView already has a parent, skipping addView");
                        }
                    } else {
                        Log.w("OverlayService", "Cannot add overlay view - conditions not met");
                    }
                } catch (Exception e) {
                    Log.e("OverlayService", "Failed to add overlay view: " + e.getMessage());
                    e.printStackTrace();
                    // Clean up on failure
                    if (flutterView != null) {
                        try {
                            flutterView.detachFromFlutterEngine();
                        } catch (Exception ex) {
                            Log.e("OverlayService", "Error detaching flutter view: " + ex.getMessage());
                        }
                        flutterView = null;
                    }
                    windowManager = null;
                    isRunning = false;
                    stopSelf();
                }
            } catch (Exception e) {
                Log.e("OverlayService", "Failed to schedule overlay view addition: " + e.getMessage());
                e.printStackTrace();
                isRunning = false;
                stopSelf();
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Critical error in initOverlay: " + e.getMessage());
            e.printStackTrace();
            // Clean up on critical failure
            if (flutterView != null) {
                try {
                    flutterView.detachFromFlutterEngine();
                } catch (Exception ex) {
                    Log.e("OverlayService", "Error detaching flutter view: " + ex.getMessage());
                }
                flutterView = null;
            }
            windowManager = null;
            isRunning = false;
            stopSelf();
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            // Obtém a altura total do display (incluindo áreas de sistema)
            int realHeight = metrics.getBounds().height();
            // Replica a lógica original (embora você possa ajustar conforme a necessidade)
            return inPortrait()
                    ? realHeight + statusBarHeightPx() + navigationBarHeightPx()
                    : realHeight + statusBarHeightPx();
        } else { // Versões antigas
            Display display = windowManager.getDefaultDisplay();
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            return inPortrait()
                    ? dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                    : dm.heightPixels + statusBarHeightPx();
        }
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            if (mResources == null) {
                Log.e("OverlayService", "Resources is null in statusBarHeightPx");
                return dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
            
            try {
                int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

                if (statusBarHeightId > 0) {
                    mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
                } else {
                    mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
                }
            } catch (Exception e) {
                Log.e("OverlayService", "Error getting status bar height: " + e.getMessage());
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            if (mResources == null) {
                Log.e("OverlayService", "Resources is null in navigationBarHeightPx");
                return dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
            
            try {
                int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

                if (navBarHeightId > 0) {
                    mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
                } else {
                    mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
                }
            } catch (Exception e) {
                Log.e("OverlayService", "Error getting navigation bar height: " + e.getMessage());
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }

    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED; // Removido FLAG_LAYOUT_INSET_DECOR

            // Verificação de opacidade para Android 12+ (API 31)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1;
            }

            // Atualiza o layout da view
            windowManager.updateViewLayout(flutterView, params);
            if (result != null) {
                result.success(true);
            }
        } else {
            if (result != null) {
                result.success(false);
            }
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        safeSurfaceOperation(() -> {
            if (windowManager != null && flutterView != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
                params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
                WindowSetup.enableDrag = enableDrag;
                windowManager.updateViewLayout(flutterView, params);
                Log.d("OverlayService", "Overlay resized to: " + params.width + "x" + params.height);
                if (result != null) {
                    result.success(true);
                }
            } else {
                if (result != null) {
                    result.success(false);
                }
            }
        }, "resizeOverlay");
    }

    private static boolean moveOverlayInternal(int x, int y, @Nullable MethodChannel.Result result) {
        if (instance != null) {
            instance.safeSurfaceOperation(() -> {
                if (instance.flutterView != null && instance.windowManager != null) {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                    // x and y are already in pixels when called from initOverlay
                    params.x = (x == -1999 || x == -1) ? -1 : x;
                    params.y = y;
                    instance.windowManager.updateViewLayout(instance.flutterView, params);
                    Log.d("OverlayService", "Overlay moved to: " + params.x + "," + params.y);
                    if (result != null) result.success(true);
                } else {
                    if (result != null) result.success(false);
                }
            }, "moveOverlayInternal");
            return true;
        }
        if (result != null) result.success(false);
        return false;
    }

    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> position = new HashMap<>();
            position.put("x", instance.pxToDp(params.x));
            position.put("y", instance.pxToDp(params.y));
            return position;
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                params.y = instance.dpToPx(y);
                instance.windowManager.updateViewLayout(instance.flutterView, params);
                Log.d("OverlayService", "Overlay moved to dp position: " + x + "," + y + " (pixels: " + params.x + "," + params.y + ")");
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public void onCreate() { // Get the cached FlutterEngine
        // Initialize resources early to prevent null pointer exceptions
        mResources = getApplicationContext().getResources();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        registerScreenUnlockReceiver();
        
        // Register configuration change receiver
        IntentFilter configFilter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        registerReceiver(configurationChangeReceiver, configFilter);
        
        // Register app lifecycle receiver to prevent crashes during transitions
        IntentFilter lifecycleFilter = new IntentFilter();
        lifecycleFilter.addAction(Intent.ACTION_SCREEN_OFF);
        lifecycleFilter.addAction(Intent.ACTION_SCREEN_ON);
        lifecycleFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(appLifecycleReceiver, lifecycleFilter);
        
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);

        if (flutterEngine == null) {
            // Handle the error if engine is not found
            Log.e("OverlayService", "Flutter engine not found, hence creating new flutter engine");
            try {
                FlutterEngineGroup engineGroup = new FlutterEngineGroup(this);
                DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                        FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                        "overlayMain"); // "overlayMain" is custom entry point

                flutterEngine = engineGroup.createAndRunEngine(this, entryPoint);

                // Cache the created FlutterEngine for future use
                FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, flutterEngine);
            } catch (Exception e) {
                Log.e("OverlayService", "Failed to create Flutter engine: " + e.getMessage());
                e.printStackTrace();
                // Don't stop the service, try to continue with null engine
            }
        }

        // Create the MethodChannel with the properly initialized FlutterEngine
        if (flutterEngine != null && isEngineValid(flutterEngine)) {
            flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
            overlayMessageChannel = new BasicMessageChannel(flutterEngine.getDartExecutor(),
                    OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
            
            // Set up the method call handler only after flutterChannel is initialized
            flutterChannel.setMethodCallHandler((call, result) -> {
                switch (call.method) {
                    case "updateFlag":
                        String flag = call.argument("flag");
                        updateOverlayFlag(result, flag);
                        break;
                    case "updateOverlayPosition":
                        int x = call.argument("x");
                        int y = call.argument("y");
                        moveOverlayInternal(x, y, result);
                        break;
                    case "resizeOverlay":
                        int width = call.argument("width");
                        int height = call.argument("height");
                        boolean enableDrag = call.argument("enableDrag");
                        resizeOverlay(width, height, enableDrag, result);
                        break;
                    default:
                        result.notImplemented();
                }
            });
        }

        // Try to start as foreground service with proper error handling
        // Only attempt if we haven't already started as foreground service
        if (!isForegroundService) {
            tryStartForegroundService();
        }

        instance = this;
    }

    /**
     * Attempts to start the service as a foreground service with proper conditions
     * This method checks if foreground service can be started and handles exceptions gracefully
     */
    private void tryStartForegroundService() {
        tryStartForegroundService(false);
    }
    
    private void tryStartForegroundService(boolean forceStart) {
        if (isForegroundService) {
            Log.d("OverlayService", "Service is already a foreground service");
            return;
        }

        // Check if we can start foreground service
        // If forceStart is true, we MUST call startForeground to avoid timeout
        if (forceStart) {
            Log.d("OverlayService", "Force starting foreground service to avoid timeout");
            // Force foreground service start even if permissions are questionable
        } else if (!canStartForegroundService(this, forceStart)) {
            Log.w("OverlayService", "Cannot start foreground service - missing permissions or restrictions");
            return;
        }

        try {
            // Create notification channel first
            createNotificationChannel();
            
            // Create notification
            Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

            int notifyIcon = getDrawableResourceId("mipmap", "ic_launcher_notification");
            Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                    .setContentTitle(WindowSetup.overlayTitle)
                    .setContentText(WindowSetup.overlayContent)
                    .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                    .setContentIntent(pendingIntent)
                    .setVisibility(WindowSetup.notificationVisibility)
                    .setOngoing(true)
                    .setSound(null)
                    .setVibrate(new long[]{0L})
                    .build();
            notification.flags |= Notification.FLAG_NO_CLEAR;

            // Start foreground service with appropriate type
            if (Build.VERSION.SDK_INT >= 34) {
                int foregroundType = 0;
                try {
                    foregroundType = (int) ServiceInfo.class
                            .getField("FOREGROUND_SERVICE_TYPE_SPECIAL_USE").get(null);
                } catch (Exception e) {
                    Log.w("OverlayService", "Could not get FOREGROUND_SERVICE_TYPE_SPECIAL_USE, using 0: " + e.getMessage());
                    foregroundType = 0;
                }
                startForeground(OverlayConstants.NOTIFICATION_ID, notification, foregroundType);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(OverlayConstants.NOTIFICATION_ID, notification);
            }
            
            isForegroundService = true;
            Log.d("OverlayService", "Service successfully started as foreground service");
            
        } catch (SecurityException e) {
            Log.w("OverlayService", "SecurityException when starting foreground service: " + e.getMessage());
            isForegroundService = false;
        } catch (IllegalStateException e) {
            Log.w("OverlayService", "IllegalStateException when starting foreground service: " + e.getMessage());
            isForegroundService = false;
        } catch (Exception e) {
            if (e instanceof android.app.ForegroundServiceStartNotAllowedException) {
                Log.w("OverlayService", "ForegroundServiceStartNotAllowedException: " + e.getMessage());
            } else {
                Log.e("OverlayService", "Unexpected error starting foreground service: " + e.getMessage());
            }
            isForegroundService = false;
        }
    }

    /**
     * Attempts to promote the service to foreground if it wasn't started as such
     * This can be called when the service context changes and allows foreground service
     */
    public void tryPromoteToForeground() {
        if (isForegroundService) {
            Log.d("OverlayService", "Service is already a foreground service");
            return;
        }

        tryStartForegroundService();
    }

    /**
     * Checks if the current context allows starting foreground services
     * @return true if foreground service is allowed, false otherwise
     */
    public static boolean canStartForegroundService(Context context) {
        return canStartForegroundService(context, false);
    }
    
    public static boolean canStartForegroundService(Context context, boolean forceStart) {
        try {
            // For Android 12+ (API 31+), be more conservative about starting foreground services
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Check if we have the required permissions
                boolean hasForegroundServicePermission = context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) == 
                       android.content.pm.PackageManager.PERMISSION_GRANTED;
                
                boolean hasSpecialUsePermission = context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE) == 
                       android.content.pm.PackageManager.PERMISSION_GRANTED;
                
                if (!hasForegroundServicePermission || !hasSpecialUsePermission) {
                    Log.w("OverlayService", "Missing required foreground service permissions for Android 12+");
                    return false;
                }
            }

            // Check if notification manager is available
            NotificationManager notificationManager = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (notificationManager == null) {
                Log.w("OverlayService", "NotificationManager is not available");
                return false;
            }

            // For Android 12+, check if notifications are enabled
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!notificationManager.areNotificationsEnabled()) {
                    Log.w("OverlayService", "Notifications are not enabled");
                    return false;
                }
            }
            
            // For Android 12+, be more conservative - only allow if we're sure it will work
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (forceStart) {
                    Log.d("OverlayService", "Android 12+ detected but force start requested - allowing foreground service to avoid timeout");
                    return true; // Allow if force start to avoid timeout
                } else {
                    Log.d("OverlayService", "Android 12+ detected - being conservative about foreground service start");
                    return false; // Conservative approach - don't start foreground service on Android 12+
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.w("OverlayService", "Error checking foreground service capability: " + e.getMessage());
            return false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_LOW);
            serviceChannel.setSound(null, null); 
            serviceChannel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(name, resType,
                getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        if (mResources == null) {
            Log.e("OverlayService", "Resources is null in dpToPx");
            return dp; // Return original value as fallback
        }
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        if (mResources == null) {
            Log.e("OverlayService", "Resources is null in pxToDp");
            return px; // Return original value as fallback
        }
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        if (mResources == null) {
            Log.e("OverlayService", "Resources is null in inPortrait");
            return true; // Default to portrait as fallback
        }
        try {
            return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        } catch (Exception e) {
            Log.e("OverlayService", "Error checking orientation: " + e.getMessage());
            return true; // Default to portrait as fallback
        }
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager != null && WindowSetup.enableDrag && flutterView != null && isSurfaceSafe()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    if (!dragging && dx * dx + dy * dy < 25) {
                        return false;
                    }
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    // Handle movement based on gravity - only invert X for RIGHT gravity
                    int xx, yy;
                    
                    if ((params.gravity & Gravity.RIGHT) != 0) {
                        // For RIGHT gravity, invert X movement for intuitive dragging
                        xx = params.x - (int) dx;
                    } else {
                        // For LEFT gravity, normal movement
                        xx = params.x + (int) dx;
                    }
                    
                    // Y movement is always normal (no inversion needed)
                    yy = params.y + (int) dy;
                    params.x = xx;
                    params.y = yy;
                    if (windowManager != null) {
                        try {
                            windowManager.updateViewLayout(flutterView, params);
                        } catch (Exception e) {
                            Log.e("OverlayService", "Error updating view layout during drag: " + e.getMessage());
                            // Mark surface as invalid if update fails
                            isSurfaceValid.set(false);
                        }
                    }
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (!WindowSetup.positionGravity.equals("none")) {
                        if (windowManager == null)
                            return false;
                        windowManager.updateViewLayout(flutterView, params);
                        mTrayTimerTask = new TrayAnimationTimerTask();
                        mTrayAnimationTimer = new Timer();
                        mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params;

        public TrayAnimationTimerTask() {
            super();
            if (flutterView != null) {
                params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            } else {
                return; // Exit early if flutterView is null
            }
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0
                            : szWindow.x - flutterView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    break;
            }
        }

        @Override
        public void run() {
            // Direct execution without handler for better performance
            if (params == null || flutterView == null) {
                return; // Exit early if params or flutterView is null
            }
            params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
            params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
            if (windowManager != null) {
                windowManager.updateViewLayout(flutterView, params);
            }
            if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                TrayAnimationTimerTask.this.cancel();
                if (mTrayAnimationTimer != null) {
                    mTrayAnimationTimer.cancel();
                }
            }
        }
    }
    private void bringOverlayToFront() {
        safeSurfaceOperation(() -> {
            if (flutterView != null && flutterView.getParent() != null && windowManager != null) {
                try {
                    WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                    
                    // Remove and re-add view to bring to front
                    windowManager.removeView(flutterView);
                    windowManager.addView(flutterView, layoutParams);
                    
                    Log.d("OverlayService", "Overlay trazido para frente com sucesso.");
                } catch (Exception e) {
                    Log.e("OverlayService", "Erro ao trazer overlay para frente: " + e.getMessage());
                }
            } else {
                Log.w("OverlayService", "FlutterView não está anexado ao WindowManager.");
            }
        }, "bringOverlayToFront");
    }

}

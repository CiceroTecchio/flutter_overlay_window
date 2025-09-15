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
import android.os.Looper;
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

    private Handler mAnimationHandler = new Handler();
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

    private BroadcastReceiver screenUnlockReceiver;
    private boolean isReceiverRegistered = false;
    private final Object lock = new Object();
    private Handler handler = new Handler(Looper.getMainLooper());
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
                        flutterEngine.getLifecycleChannel().appIsResumed();
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
                // Delay the update to let the system settle
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                }, 500);
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
        registerReceiver(screenUnlockReceiver, filter);
        isReceiverRegistered = true;
    }

    /**
     * Safe engine validation to prevent segfaults
     */
    private boolean isEngineValid(FlutterEngine engine) {
        if (engine == null) return false;
        
        try {
            // Check if engine is not destroyed
            if (engine.getDartExecutor() == null) {
                Log.w("OverlayService", "Engine DartExecutor is null");
                return false;
            }
            
            // Check if lifecycle channel is available
            if (engine.getLifecycleChannel() == null) {
                Log.w("OverlayService", "Engine LifecycleChannel is null");
                return false;
            }
            
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
        if (isDestroyed.get()) {
            Log.w("OverlayService", "Service destroyed, cannot access engine");
            return null;
        }
        
        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null && isEngineValid(engine)) {
                return engine;
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Error accessing engine: " + e.getMessage());
        }
        return null;
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
                // Check if FlutterView is properly attached
                if (flutterView.getParent() == null) {
                    Log.w("OverlayService", "FlutterView not attached to parent");
                    return false;
                }
                
                // Check if the view is visible and has valid dimensions
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

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        
        // Mark service as destroyed to prevent further operations
        isDestroyed.set(true);
        isEngineValid.set(false);
        isSurfaceValid.set(false);

        try {
            // Safe engine access
            FlutterEngine engine = getValidEngine();
            if (engine != null) {
                Log.d("OverLay", "Parando som do ringtone");
                try {
                    new MethodChannel(engine.getDartExecutor(), "my_custom_overlay_channel")
                            .invokeMethod("onOverlayClosed", null);
                } catch (Exception e) {
                    Log.e("OverLay", "Error calling onOverlayClosed: " + e.getMessage());
                }
            } else {
                Log.d("OverLay", "FlutterEngine nulo, não foi possível chamar onOverlayClosed");
            }
        } catch (Exception e) {
            Log.e("OverLay", "Falha ao parar som do ringtone", e);
        }

        if (windowManager != null && flutterView != null) {
            try {
                windowManager.removeView(flutterView);
            } catch (Exception e) {
                Log.e("OverLay", "Erro ao remover flutterView", e);
            }
            flutterView.detachFromFlutterEngine();
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
        if (!isForegroundService) {
            tryStartForegroundService();
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
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);

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

        // Safe engine access with validation
        FlutterEngine engine = getValidEngine();
        if (engine == null) {
            Log.e("OverlayService", "FlutterEngine não encontrado no cache ou inválido");
            isRunning = false;
            stopSelf();
            return;
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

            engine.getLifecycleChannel().appIsResumed();
            
            // Create FlutterView with proper error handling
            try {
                // Disable Impeller renderer to prevent surface crashes
                // This is a safety measure for overlay windows
                System.setProperty("flutter.impeller.enabled", "false");
                
                flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
                
                // Add surface error listener to catch surface-related crashes
                flutterView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        Log.d("OverlayService", "FlutterView attached to window");
                        isSurfaceValid.set(true);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        Log.d("OverlayService", "FlutterView detached from window");
                        isSurfaceValid.set(false);
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
                
                flutterView.attachToFlutterEngine(engine);
                flutterView.setFitsSystemWindows(true);
                flutterView.setFocusable(true);
                flutterView.setFocusableInTouchMode(true);
                flutterView.setBackgroundColor(Color.TRANSPARENT);
                flutterView.setOnTouchListener(this);
                
                // Mark surface as valid after successful creation
                isSurfaceValid.set(true);
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
                // Add a small delay to let the FlutterView settle before adding to window manager
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (windowManager != null && flutterView != null && isRunning) {
                            windowManager.addView(flutterView, params);
                            Log.d("OverlayService", "Overlay view added successfully at position: " + initialX + "," + initialY + " with gravity: " + params.gravity);
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
                }, 100); // Small delay to let FlutterView settle
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
        tryStartForegroundService();

        instance = this;
    }

    /**
     * Attempts to start the service as a foreground service with proper conditions
     * This method checks if foreground service can be started and handles exceptions gracefully
     */
    private void tryStartForegroundService() {
        if (isForegroundService) {
            Log.d("OverlayService", "Service is already a foreground service");
            return;
        }

        // Check if we can start foreground service
        if (!canStartForegroundService(this)) {
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
            Log.e("OverlayService", "Unexpected error starting foreground service: " + e.getMessage());
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
        try {
            // Check if we have the required permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                boolean hasForegroundServicePermission = context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) == 
                       android.content.pm.PackageManager.PERMISSION_GRANTED;
                
                if (!hasForegroundServicePermission) {
                    Log.w("OverlayService", "Missing FOREGROUND_SERVICE permission");
                    return false;
                }
            }

            // For Android 12+ (API 31+), check for special use permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolean hasSpecialUsePermission = context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE) == 
                       android.content.pm.PackageManager.PERMISSION_GRANTED;
                
                if (!hasSpecialUsePermission) {
                    Log.w("OverlayService", "Missing FOREGROUND_SERVICE_SPECIAL_USE permission for Android 12+");
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
        if (windowManager != null && WindowSetup.enableDrag && flutterView != null) {
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
                    // Convert to screen coordinates for consistent dragging behavior
                    int screenX, screenY;
                    int xx, yy;
                    
                    if ((params.gravity & Gravity.RIGHT) != 0) {
                        // For RIGHT gravity, convert to screen coordinates
                        screenX = szWindow.x - params.x - flutterView.getWidth();
                    } else {
                        // For LEFT gravity, x is already in screen coordinates
                        screenX = params.x;
                    }
                    
                    if ((params.gravity & Gravity.BOTTOM) != 0) {
                        // For BOTTOM gravity, convert to screen coordinates
                        screenY = szWindow.y - params.y - flutterView.getHeight();
                    } else {
                        // For TOP gravity, y is already in screen coordinates
                        screenY = params.y;
                    }
                    
                    // Apply movement in screen coordinates
                    screenX += (int) dx;
                    screenY += (int) dy;
                    
                    // Convert back to gravity-specific coordinates
                    if ((params.gravity & Gravity.RIGHT) != 0) {
                        xx = szWindow.x - screenX - flutterView.getWidth();
                    } else {
                        xx = screenX;
                    }
                    
                    if ((params.gravity & Gravity.BOTTOM) != 0) {
                        yy = szWindow.y - screenY - flutterView.getHeight();
                    } else {
                        yy = screenY;
                    }
                    params.x = xx;
                    params.y = yy;
                    if (windowManager != null) {
                        windowManager.updateViewLayout(flutterView, params);
                        Log.d("OverlayService", "Drag: dx=" + dx + ", dy=" + dy + ", gravity=" + params.gravity + 
                              ", screenX=" + screenX + ", screenY=" + screenY + ", new pos=" + xx + "," + yy);
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
            mAnimationHandler.post(() -> {
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
            });
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

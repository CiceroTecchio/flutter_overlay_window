package flutter.overlay.window.flutter_overlay_window;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
// Removed Handler and Looper imports - using direct execution

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.util.Log;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.View;
import android.content.res.Resources;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import flutter.overlay.window.flutter_overlay_window.WindowSetup;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import flutter.overlay.window.flutter_overlay_window.FlutterEngineManager;

public class LockScreenOverlayActivity extends Activity {
    private FlutterView flutterView;
    private FlutterEngine flutterEngine;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;
    private Resources resources;
    public static boolean isRunning = false;
    private static final String TAG = "LockScreenOverlay";
    private boolean isDestroyed = false;
    private final Object engineLock = new Object();
    

    private BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Broadcast recebido, fechando activity");
            safeFinish();
            isRunning = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate chamado");
        
        try {
            // CRITICAL: Disable accessibility for the entire activity BEFORE any other setup
            disableAccessibilityForActivity();
            
            // Set up window flags first
            setupWindowFlags();
            
            // Register receiver
            registerCloseReceiver();
            
            // Initialize resources
            resources = getResources();

            // Safe engine retrieval with proper synchronization
            synchronized (engineLock) {
                if (isDestroyed) return;
                
                flutterEngine = FlutterEngineManager.getEngine(OverlayConstants.CACHED_TAG);
                if (flutterEngine == null) {
                    Log.e(TAG, "FlutterEngine não encontrado");
                    safeFinish();
                    return;
                }
            }

            // Safe engine lifecycle management
            try {
                if (flutterEngine.getLifecycleChannel() != null) {
                    flutterEngine.getLifecycleChannel().appIsResumed();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error resuming engine: " + e.getMessage());
                safeFinish();
                return;
            }

            isRunning = true;
            
            // Create channels only if engine is valid
            synchronized (engineLock) {
                if (isDestroyed || !FlutterEngineManager.isEngineValid(flutterEngine)) {
                    safeFinish();
                    return;
                }
                
                try {
                    flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
                    overlayMessageChannel = new BasicMessageChannel<>(flutterEngine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
                } catch (Exception e) {
                    Log.e(TAG, "Error creating channels: " + e.getMessage());
                    safeFinish();
                    return;
                }
            }

            setupMethodChannels();

            Intent intent = getIntent();
            int width = intent.getIntExtra("width", 300);
            int height = intent.getIntExtra("height", 300);

            final int pxWidth = (width == -1999 || width == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(width);
            final int pxHeight = (height == -1999 || height == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(height);

            // Create FlutterView on main thread with proper error handling
            createFlutterView(pxWidth, pxHeight);
            
        } catch (Exception e) {
            Log.e(TAG, "Critical error in onCreate: " + e.getMessage());
            e.printStackTrace();
            safeFinish();
        }
    }

    /**
     * Completely disables accessibility for the entire activity
     * This prevents auto-click apps from interfering with the overlay
     */
    private void disableAccessibilityForActivity() {
        try {
            Log.i(TAG, "🔒 DISABLING ACCESSIBILITY FOR LOCKSCREEN OVERLAY...");
            
            // Disable accessibility for the entire activity window
            getWindow().getDecorView().setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            getWindow().getDecorView().setAccessibilityDelegate(null);
            getWindow().getDecorView().setContentDescription(null);
            
            // Note: Activity doesn't have setImportantForAccessibility method
            // Accessibility is disabled through the window and views
            
            // Disable accessibility for the entire view hierarchy
            ViewGroup rootView = (ViewGroup) getWindow().getDecorView();
            disableAccessibilityRecursively(rootView);
            
            Log.i(TAG, "✅ ACCESSIBILITY COMPLETELY DISABLED FOR LOCKSCREEN OVERLAY");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error disabling accessibility: " + e.getMessage());
        }
    }
    
    /**
     * Recursively disables accessibility for all views in the hierarchy
     */
    private void disableAccessibilityRecursively(ViewGroup parent) {
        try {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                child.setAccessibilityDelegate(null);
                child.setContentDescription(null);
                
                if (child instanceof ViewGroup) {
                    disableAccessibilityRecursively((ViewGroup) child);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error disabling accessibility recursively: " + e.getMessage());
        }
    }

    private void setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            setFinishOnTouchOutside(false);
        }

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        
        // CRITICAL: Disable accessibility for the entire activity
        getWindow().getDecorView().setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        getWindow().getDecorView().setAccessibilityDelegate(null);
        
        // CRITICAL: Block FlutterJNI.updateSemantics at JNI level
        blockFlutterJNIUpdateSemantics();
    }

    private void registerCloseReceiver() {
        IntentFilter filter = new IntentFilter("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, filter);
        }
    }

    private void setupMethodChannels() {
        flutterChannel.setMethodCallHandler((call, result) -> {
            try {
                if ("close".equals(call.method)) {
                    safeFinish();
                    result.success(true);
                } else {
                    result.notImplemented();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling method call: " + e.getMessage());
                result.error("ERROR", "Method call failed", e.getMessage());
            }
        });

        overlayMessageChannel.setMessageHandler((message, reply) -> {
            try {
                if (WindowSetup.messenger != null) {
                    WindowSetup.messenger.send(message);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending message: " + e.getMessage());
            }
        });
    }

    private void createFlutterView(final int pxWidth, final int pxHeight) {
        // Direct execution without handler for better performance
        try {
            if (isDestroyed) return;
            
            // Re-validate engine state before creating view
            synchronized (engineLock) {
                if (!FlutterEngineManager.isEngineValid(flutterEngine)) {
                    Log.e(TAG, "Engine is invalid during view creation");
                    safeFinish();
                    return;
                }
            }

            // Create FlutterView with comprehensive accessibility protection
            FlutterTextureView customTextureView = new FlutterTextureView(this) {
                @Override
                public void onAttachedToWindow() {
                    super.onAttachedToWindow();
                    // Force disable accessibility when attached
                    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    
                    // Apply custom accessibility delegate to prevent crashes
                    setAccessibilityDelegate(new View.AccessibilityDelegate() {
                        @Override
                        public void sendAccessibilityEvent(View host, int eventType) {
                            Log.d(TAG, "LockScreen FlutterTextureView blocked accessibility event: " + eventType);
                            // Block all events
                        }
                        
                        @Override
                        public boolean performAccessibilityAction(View host, int action, Bundle args) {
                            Log.d(TAG, "LockScreen FlutterTextureView blocked accessibility action: " + action);
                            return false;
                        }
                        
                        @Override
                        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
                            Log.d(TAG, "LockScreen FlutterTextureView blocked accessibility event initialization");
                            // Block initialization
                        }
                        
                        // Note: onInitializeAccessibilityNodeInfo is not available in this API level
                        // The method is blocked through other means
                    });
                    
                    Log.d(TAG, "LockScreen FlutterTextureView attached - accessibility completely disabled");
                }
                
                @Override
                public void onDetachedFromWindow() {
                    // Don't call super to prevent cleanup that might trigger semantics
                    Log.d(TAG, "LockScreen FlutterTextureView detached - preventing semantics cleanup");
                }
                
                // Note: requestSendAccessibilityEvent is not available in FlutterTextureView
                // The method is blocked through AccessibilityDelegate
            };
            
            flutterView = new FlutterView(this, customTextureView);
            
            // BALANCED SOLUTION: Allow user interaction but prevent accessibility crashes
            flutterView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            flutterView.setContentDescription(null);
            // Keep focusable for user interaction
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            
            // Add comprehensive accessibility protection that blocks ALL accessibility
            flutterView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void sendAccessibilityEvent(View host, int eventType) {
                    // Block ALL accessibility events to prevent crashes
                    Log.d(TAG, "🚫 LockScreen BLOCKED accessibility event: " + eventType);
                    return; // Block all events
                }
                
                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    // Block ALL accessibility actions to prevent crashes
                    Log.d(TAG, "🚫 LockScreen BLOCKED accessibility action: " + action);
                    return false; // Block all actions
                }
                
                @Override
                public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
                    // Block initialization to prevent crashes
                    Log.d(TAG, "🚫 LockScreen BLOCKED accessibility event initialization");
                    return; // Block initialization
                }
                
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                    // Block node info initialization to prevent crashes
                    Log.d(TAG, "🚫 LockScreen BLOCKED accessibility node info initialization");
                    return; // Block initialization
                }
            });
            
            // Add surface error listener to catch surface-related crashes
            flutterView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    Log.d(TAG, "FlutterView attached to window");
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    Log.d(TAG, "FlutterView detached from window");
                }
            });
            
            // Configure view properties for interaction
            flutterView.setBackgroundColor(Color.TRANSPARENT);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            flutterView.setClickable(true);
            flutterView.setLongClickable(true);

            // Create layout
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(pxWidth, pxHeight);
            layoutParams.gravity = Gravity.CENTER;

            FrameLayout root = new FrameLayout(this);
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            
            // Make root container invisible to accessibility
            root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.setAccessibilityDelegate(null);
            
            // Ensure root container allows interaction
            root.setClickable(true);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            
            root.addView(flutterView, layoutParams);

            setContentView(root);
            
            // CRITICAL: Attach engine AFTER setContentView to prevent accessibility issues
            synchronized (engineLock) {
                if (isDestroyed || !FlutterEngineManager.isEngineValid(flutterEngine)) {
                    Log.e(TAG, "Engine invalid during view attachment");
                    safeFinish();
                    return;
                }
                
                try {
                    flutterView.attachToFlutterEngine(flutterEngine);
                    Log.d(TAG, "FlutterView attached to engine after setContentView");
                } catch (Exception e) {
                    Log.e(TAG, "Error attaching view to engine: " + e.getMessage());
                    safeFinish();
                    return;
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating FlutterView: " + e.getMessage());
            e.printStackTrace();
            safeFinish();
        }
    }

    @Override
    public void onDestroy() {
        try {
            isDestroyed = true;
            isRunning = false;
            
            if (closeReceiver != null) {
                try {
                    unregisterReceiver(closeReceiver);
                } catch (Exception e) {
                    Log.w(TAG, "Error unregistering receiver: " + e.getMessage());
                }
            }
            
            Log.d(TAG, "Destroying the overlay lock screen window service");
            
            // Safe engine access for cleanup
            synchronized (engineLock) {
                if (flutterEngine != null && FlutterEngineManager.isEngineValid(flutterEngine)) {
                    try {
                        Log.d(TAG, "Parando som do ringtone");
                        new MethodChannel(flutterEngine.getDartExecutor(), "my_custom_overlay_channel").invokeMethod("onOverlayClosed", null);
                    } catch (Exception e) {
                        Log.d(TAG, "Falha ao parar som do ringtone: " + e.getMessage());
                    }
                }
            }
            
            // Safe view cleanup
            if (flutterView != null) {
                try {
                    flutterView.detachFromFlutterEngine();
                } catch (Exception e) {
                    Log.w(TAG, "Error detaching view from engine: " + e.getMessage());
                }
                flutterView = null;
            }
            
            // Clear references
            synchronized (engineLock) {
                flutterEngine = null;
                flutterChannel = null;
                overlayMessageChannel = null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage());
            e.printStackTrace();
        } finally {
            super.onDestroy();
        }
    }

    private int dpToPx(int dp) {
        try {
            if (resources != null) {
                return (int) (dp * resources.getDisplayMetrics().density);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error converting dp to px: " + e.getMessage());
        }
        return dp; // fallback
    }
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent chamado – activity reordenada para frente.");
        setIntent(intent); // Atualiza intent se quiser usar extras
    }
    @Override
    public void onBackPressed() {
        // Não chama super, assim botão voltar não fecha
        Log.d(TAG, "Botão voltar desativado");
    }

    /**
     * Safe finish method that prevents multiple calls
     */
    private void safeFinish() {
        if (!isDestroyed) {
            isDestroyed = true;
            isRunning = false;
            finish();
        }
    }
}

package flutter.overlay.window.flutter_overlay_window;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.util.Log;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.Gravity;
import android.content.res.Resources;
import android.widget.FrameLayout;
import flutter.overlay.window.flutter_overlay_window.WindowSetup;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;

public class LockScreenOverlayActivity extends Activity {
    private FlutterView flutterView;
    private FlutterEngine flutterEngine;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;
    private Resources resources;
    public static boolean isRunning = false;
    private BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("LockScreenOverlay", "Broadcast recebido, fechando activity");
            finish();
            isRunning = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("LockScreenOverlay", "onCreate chamado");
        IntentFilter filter = new IntentFilter("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, filter);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            setFinishOnTouchOutside(false);
        }

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        
        resources = getResources();

        flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (flutterEngine == null) {
            Log.e("LockScreenOverlay", "FlutterEngine não encontrado");
            finish();
            isRunning = false;
            return;
        }

        // Validate engine state before proceeding
        try {
            if (!flutterEngine.getRenderer().isDisplayingFlutterUi()) {
                Log.w("LockScreenOverlay", "FlutterEngine not ready for rendering, waiting...");
                // Wait a bit for the engine to be ready
                new Handler(getMainLooper()).postDelayed(() -> {
                    if (!isFinishing() && isRunning) {
                        // Retry initialization
                        recreate();
                    }
                }, 200);
                return;
            }
        } catch (Exception e) {
            Log.w("LockScreenOverlay", "Could not check engine state, proceeding anyway: " + e.getMessage());
            // Continue if we can't check the state (older Flutter versions)
        }

        try {
            flutterEngine.getLifecycleChannel().appIsResumed();
        } catch (Exception e) {
            Log.e("LockScreenOverlay", "Error resuming engine: " + e.getMessage());
            finish();
            return;
        }

        isRunning = true;
        flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
        overlayMessageChannel = new BasicMessageChannel<>(flutterEngine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);

        flutterChannel.setMethodCallHandler((call, result) -> {
            if ("close".equals(call.method)) {
                finish();
                isRunning = false;
                result.success(true);
            } else {
                result.notImplemented();
            }
        });

        overlayMessageChannel.setMessageHandler((message, reply) -> {
            try {
                WindowSetup.messenger.send(message);
            } catch (Exception e) {
                Log.e("LockScreenOverlay", "Error sending message: " + e.getMessage());
            }
        });

        Intent intent = getIntent();
        int width = intent.getIntExtra("width", 300);
        int height = intent.getIntExtra("height", 300);

       
        final int pxWidth = (width == -1999 || width == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(width);
        final int pxHeight = (height == -1999 || height == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(height);

        new Handler(getMainLooper()).post(() -> {
            try {
                // Validate engine state again before creating view
                if (flutterEngine == null) {
                    Log.e("LockScreenOverlay", "Engine is null");
                    finish();
                    return;
                }
                
                try {
                    if (!flutterEngine.getRenderer().isDisplayingFlutterUi()) {
                        Log.w("LockScreenOverlay", "Engine not ready for view creation, waiting...");
                        // Wait a bit and retry
                        new Handler(getMainLooper()).postDelayed(() -> {
                            if (!isFinishing() && isRunning) {
                                // Retry view creation
                                recreate();
                            }
                        }, 200);
                        return;
                    }
                } catch (Exception e) {
                    Log.w("LockScreenOverlay", "Could not check engine state, proceeding anyway: " + e.getMessage());
                    // Continue if we can't check the state (older Flutter versions)
                }

                flutterView = new FlutterView(this, new FlutterTextureView(this));
                flutterView.attachToFlutterEngine(flutterEngine);
                flutterView.setBackgroundColor(Color.TRANSPARENT);
                flutterView.setFocusable(true);
                flutterView.setFocusableInTouchMode(true);

                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(pxWidth, pxHeight);
                layoutParams.gravity = Gravity.CENTER;

                FrameLayout root = new FrameLayout(this);
                root.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                root.addView(flutterView, layoutParams);

                setContentView(root);
            } catch (Exception e) {
                Log.e("LockScreenOverlay", "Error creating FlutterView: " + e.getMessage());
                e.printStackTrace();
                finish();
            }
        });
    }

    @Override
    
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(closeReceiver);
        Log.d("LockScreenOverlay", "Destroying the overlay lock screen window service");
        try{
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null) {
                Log.d("LockScreenOverlay", "Parando som do ringtone");
                new MethodChannel(engine.getDartExecutor(), "my_custom_overlay_channel").invokeMethod("onOverlayClosed", null);
            }
        } catch (Exception e) {
            Log.d("LockScreenOverlay", "Falha ao parar som do ringtone");
            e.printStackTrace();
        }
        
       if (flutterView != null) {
            flutterView.detachFromFlutterEngine();
            flutterView = null; // opcional
        }
        isRunning = false;
    }

    private int dpToPx(int dp) {
        return (int) (dp * resources.getDisplayMetrics().density);
    }
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d("LockScreenOverlay", "onNewIntent chamado – activity reordenada para frente.");
        setIntent(intent); // Atualiza intent se quiser usar extras
    }
    @Override
    public void onBackPressed() {
        // Não chama super, assim botão voltar não fecha
        Log.d("LockScreenOverlay", "Botão voltar desativado");
    }
}

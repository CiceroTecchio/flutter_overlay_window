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
            Log.i("LockScreenOverlay", "📡 Broadcast CLOSE recebido - Fechando LockScreenOverlayActivity");
            finish();
            isRunning = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("LockScreenOverlay", "🚀 onCreate() - Iniciando LockScreenOverlayActivity");
        
        IntentFilter filter = new IntentFilter("flutter.overlay.window.CLOSE_LOCKSCREEN_OVERLAY");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, filter);
        }
        Log.d("LockScreenOverlay", "📡 BroadcastReceiver registrado para fechamento");

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
        
        resources = getResources();

        Log.d("LockScreenOverlay", "🔍 Buscando FlutterEngine no cache global");
        flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (flutterEngine == null) {
            Log.e("LockScreenOverlay", "❌ FlutterEngine não encontrado no cache global");
            finish();
            isRunning = false;
            return;
        }
        Log.i("LockScreenOverlay", "♻️ REUTILIZANDO FlutterEngine do cache global");
        
        Log.d("LockScreenOverlay", "🔄 Resumindo FlutterEngine lifecycle");

            // Safe engine lifecycle management
            try {
                if (flutterEngine.getLifecycleChannel() != null) {
                    flutterEngine.getLifecycleChannel().appIsResumed();
                }
            } catch (Exception e) {
                Log.e("LockScreenOverlay", "Error resuming engine: " + e.getMessage());
                safeFinish();
                return;
            }

        isRunning = true;
        Log.d("LockScreenOverlay", "✅ LockScreenOverlayActivity marcado como running");
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
            WindowSetup.messenger.send(message);
        });

        Intent intent = getIntent();
        int width = intent.getIntExtra("width", 300);
        int height = intent.getIntExtra("height", 300);
        Log.d("LockScreenOverlay", "📐 Dimensões recebidas - Width: " + width + ", Height: " + height);

       
        final int pxWidth = (width == -1999 || width == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(width);
        final int pxHeight = (height == -1999 || height == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(height);
        Log.d("LockScreenOverlay", "📏 Dimensões em pixels - Width: " + pxWidth + ", Height: " + pxHeight);

        Log.d("LockScreenOverlay", "🎬 Criando FlutterView para LockScreen");
        new Handler(getMainLooper()).post(() -> {
            long startTime = System.currentTimeMillis();
            
            flutterView = new FlutterView(this, new FlutterTextureView(this));
            Log.d("LockScreenOverlay", "🔌 Conectando FlutterView ao FlutterEngine");
            flutterView.attachToFlutterEngine(flutterEngine);
            flutterView.setBackgroundColor(Color.TRANSPARENT);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            
            long creationTime = System.currentTimeMillis() - startTime;
            Log.i("LockScreenOverlay", "✅ FlutterView criada em " + creationTime + "ms");

            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(pxWidth, pxHeight);
            layoutParams.gravity = Gravity.CENTER;

            FrameLayout root = new FrameLayout(this);
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(flutterView, layoutParams);
            Log.d("LockScreenOverlay", "📱 FlutterView adicionada ao layout");

            setContentView(root);
            Log.i("LockScreenOverlay", "✅ LockScreenOverlayActivity configurada com sucesso");
        });
    }

    @Override
    public void onDestroy() {
        Log.i("LockScreenOverlay", "🗑️ onDestroy() - Iniciando destruição do LockScreenOverlayActivity");
        
        super.onDestroy();
        Log.d("LockScreenOverlay", "📡 Desregistrando closeReceiver");
        unregisterReceiver(closeReceiver);
        
        try{
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null) {
                Log.d("LockScreenOverlay", "📞 Chamando onOverlayClosed no Flutter");
                new MethodChannel(engine.getDartExecutor(), "my_custom_overlay_channel").invokeMethod("onOverlayClosed", null);
            } else {
                Log.w("LockScreenOverlay", "⚠️ FlutterEngine nulo, não foi possível chamar onOverlayClosed");
            }
        } catch (Exception e) {
            Log.e("LockScreenOverlay", "❌ Falha ao chamar onOverlayClosed", e);
            e.printStackTrace();
        }
        
       if (flutterView != null) {
            Log.d("LockScreenOverlay", "🔌 Desconectando FlutterView do FlutterEngine");
            flutterView.detachFromFlutterEngine();
            flutterView = null;
        }
        
        isRunning = false;
        Log.i("LockScreenOverlay", "✅ LockScreenOverlayActivity destruída com sucesso");
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

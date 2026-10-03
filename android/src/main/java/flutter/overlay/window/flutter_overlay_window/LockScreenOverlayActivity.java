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
    static volatile LockScreenOverlayActivity instancia;
    private boolean saiuPorAcaoDoUsuario = false;
    private int[] tamanhoPedido;
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
        if (flutterEngine == null || flutterEngine.getDartExecutor() == null) {
            Log.e("LockScreenOverlay", "❌ FlutterEngine não encontrado no cache global ou DartExecutor nulo");
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
            }

        isRunning = true;
        instancia = this;
        Log.d("LockScreenOverlay", "✅ LockScreenOverlayActivity marcado como running");
        flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
        overlayMessageChannel = new BasicMessageChannel<>(flutterEngine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);

        flutterChannel.setMethodCallHandler((call, result) -> {
            if ("close".equals(call.method)) {
                finish();
                isRunning = false;
                result.success(true);
            } else if ("resizeOverlay".equals(call.method)) {
                // O card cresce com o conteúdo (mais paradas, fonte grande) e o
                // Dart pede a altura nova por aqui — sem isto a view ficava nos
                // 450dp do intent e o card rolava por dentro.
                Integer w = call.argument("width");
                Integer h = call.argument("height");
                result.success(resizeFlutterView(w == null ? -1 : w, h == null ? -1 : h));
            } else if ("updateOverlayPosition".equals(call.method)) {
                // A view é centralizada na Activity; posição não se aplica aqui.
                result.success(false);
            } else {
                result.notImplemented();
            }
        });

        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.sendMessage(message);
        });

        Intent intent = getIntent();
        int width = intent.getIntExtra("width", 300);
        int height = intent.getIntExtra("height", 300);
        int[] medido = OverlayService.ultimoResizeDp;
        if (medido != null) {
            width = medido[0];
            height = medido[1];
        }
        Log.d("LockScreenOverlay", "📐 Dimensões recebidas - Width: " + width + ", Height: " + height);

       
        final int pxWidth = (width == -1999 || width == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(width);
        final int pxHeight = (height == -1999 || height == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(height);
        Log.d("LockScreenOverlay", "📏 Dimensões em pixels - Width: " + pxWidth + ", Height: " + pxHeight);

        Log.d("LockScreenOverlay", "🎬 Criando FlutterView para LockScreen");
        new Handler(getMainLooper()).post(() -> {
            // Fechada antes do post rodar: o onDestroy já passou e não desconectaria esta view.
            if (isFinishing() || isDestroyed()) {
                Log.w("LockScreenOverlay", "⚠️ Activity já finalizada, FlutterView não será criada");
                return;
            }
            long startTime = System.currentTimeMillis();

            OverlayService.releaseSurface();
            flutterView = new FlutterView(this, new FlutterTextureView(this));
            Log.d("LockScreenOverlay", "🔌 Conectando FlutterView ao FlutterEngine");
            flutterView.attachToFlutterEngine(flutterEngine);
            flutterView.setBackgroundColor(Color.TRANSPARENT);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            
            long creationTime = System.currentTimeMillis() - startTime;
            Log.i("LockScreenOverlay", "✅ FlutterView criada em " + creationTime + "ms");

            int[] pedido = tamanhoPedido;
            FrameLayout.LayoutParams layoutParams = pedido != null
                    ? new FrameLayout.LayoutParams(pedido[0], pedido[1])
                    : new FrameLayout.LayoutParams(pxWidth, pxHeight);
            layoutParams.gravity = Gravity.CENTER;

            FrameLayout root = new FrameLayout(this);
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(flutterView, layoutParams);
            Log.d("LockScreenOverlay", "📱 FlutterView adicionada ao layout");

            setContentView(root);
            aplicaTamanhoPedido();
            Log.i("LockScreenOverlay", "✅ LockScreenOverlayActivity configurada com sucesso");
        });
    }

    // Home na tela bloqueada: a Activity ia para o fundo, com o card tocando
    // dentro dela e nada na tela. Encerrada, o onDestroy avisa o Dart (origem
    // lockscreen), que leva o card para a janela do serviço.
    // O onUserLeaveHint sozinho não serve de sinal: lançada com a tela apagada
    // (turnScreenOn) ele dispara ~150ms depois do onCreate sem ninguém apertar
    // nada, seguido de onPause e onResume. O Home de verdade chega ao onStop;
    // o espúrio não. Apagar a tela chega ao onStop sem o hint.
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        saiuPorAcaoDoUsuario = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        saiuPorAcaoDoUsuario = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (saiuPorAcaoDoUsuario && !isFinishing()) {
            Log.i("LockScreenOverlay", "🏠 Home com o card na tela bloqueada, encerrando a Activity");
            finish();
        }
    }

    @Override
    public void onDestroy() {
        Log.i("LockScreenOverlay", "🗑️ onDestroy() - Iniciando destruição do LockScreenOverlayActivity");
        if (instancia == this) instancia = null;
        
        super.onDestroy();
        Log.d("LockScreenOverlay", "📡 Desregistrando closeReceiver");
        unregisterReceiver(closeReceiver);
        
        try{
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null && engine.getDartExecutor() != null) {
                Log.d("LockScreenOverlay", "📞 Chamando onOverlayClosed no Flutter");
                // A origem importa: fechada pelo Home com o pedido ainda tocando, o
                // Dart precisa levar o card para a janela do serviço, não calar.
                java.util.Map<String, Object> args = new java.util.HashMap<>();
                args.put("origem", "lockscreen");
                new MethodChannel(engine.getDartExecutor(), "my_custom_overlay_channel").invokeMethod("onOverlayClosed", args);
            } else {
                Log.w("LockScreenOverlay", "⚠️ FlutterEngine ou DartExecutor nulo, não foi possível chamar onOverlayClosed");
            }
        } catch (Exception e) {
            Log.e("LockScreenOverlay", "❌ Falha ao chamar onOverlayClosed", e);
            e.printStackTrace();
        }
        
       if (flutterView != null) {
            Log.d("LockScreenOverlay", "🔌 Desconectando FlutterView do FlutterEngine");
            flutterView.detachFromFlutterEngine();
            flutterView = null;
            OverlayService.reclaimSurface();
        }
        // Os canais são da engine, não desta Activity: sem devolvê-los o serviço
        // ficava com o handler de uma Activity morta pelo resto do turno.
        OverlayService.reclaimChannels();

        isRunning = false;
        Log.i("LockScreenOverlay", "✅ LockScreenOverlayActivity destruída com sucesso");
    }

    // O resize chega a qualquer momento: antes da view existir, com ela criada e
    // ainda fora do layout, ou depois. Guardar o último pedido e aplicá-lo nos
    // dois pontos (criação e aqui) é o que impede o card de ficar na altura do
    // intent, cortando os botões de aceitar/recusar.
    private boolean resizeFlutterView(int width, int height) {
        if (isFinishing() || isDestroyed()) return false;
        final int pxWidth = (width == -1999 || width == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(width);
        final int pxHeight = (height == -1999 || height == -1) ? ViewGroup.LayoutParams.MATCH_PARENT : dpToPx(height);
        tamanhoPedido = new int[]{pxWidth, pxHeight};
        Log.d("LockScreenOverlay", "📐 resize pedido: " + width + "x" + height + " (view=" + (flutterView != null) + ")");
        new Handler(getMainLooper()).post(this::aplicaTamanhoPedido);
        return true;
    }

    private void aplicaTamanhoPedido() {
        final FlutterView view = flutterView;
        final int[] pedido = tamanhoPedido;
        if (view == null || pedido == null) return;
        try {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params == null) return;
            if (params.width == pedido[0] && params.height == pedido[1]) return;
            params.width = pedido[0];
            params.height = pedido[1];
            view.setLayoutParams(params);
            Log.d("LockScreenOverlay", "📐 resize aplicado: " + pedido[0] + "x" + pedido[1] + "px");
        } catch (Exception e) {
            Log.e("LockScreenOverlay", "❌ Erro ao redimensionar a FlutterView: " + e.getMessage());
        }
    }

    void redimensiona(int width, int height) {
        resizeFlutterView(width, height);
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

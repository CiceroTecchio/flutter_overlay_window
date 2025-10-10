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
import android.os.Handler;
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
import java.util.concurrent.ConcurrentHashMap;

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

public class OverlayService extends Service implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;
    
    // Otimização: Cache de conversões DP/PX para evitar recálculos
    private static final Map<Integer, Integer> dpToPxCache = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> pxToDpCache = new ConcurrentHashMap<>();
    private static float density = -1f;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
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
                try {
                    FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
                    if (flutterEngine == null || flutterEngine.getDartExecutor() == null) {
                        Log.w("OverlayService", "⚠️ FlutterEngine or DartExecutor is null in screenReceiver");
                        return;
                    }
                    if (flutterEngine.getLifecycleChannel() != null) {
                        flutterEngine.getLifecycleChannel().appIsResumed();
                    }
                } catch (Exception e) {
                    Log.e("OverlayService", "❌ Error in screenReceiver: " + e.getMessage(), e);
                }
            }
        }
    };

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

                            FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
                            if (flutterView != null && flutterEngine != null && flutterEngine.getDartExecutor() != null) {
                                try {
                                    flutterView.attachToFlutterEngine(flutterEngine);
                                    if (flutterEngine.getLifecycleChannel() != null) {
                                        flutterEngine.getLifecycleChannel().appIsResumed();
                                    }
                                    flutterView.invalidate();
                                    flutterView.requestLayout();
                                    bringOverlayToFront();
                                    Log.d("OverlayService", "FlutterView resumido e redraw feito.");
                                } catch (Exception e) {
                                    Log.e("OverlayService", "❌ Error resuming FlutterView: " + e.getMessage(), e);
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.i("OverlayService", "🗑️ onDestroy() - Iniciando destruição do OverlayService");

        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null && engine.getDartExecutor() != null) {
                Log.d("OverlayService", "📞 Chamando onOverlayClosed no Flutter");
                new MethodChannel(engine.getDartExecutor(), "my_custom_overlay_channel")
                        .invokeMethod("onOverlayClosed", null);
            } else {
                Log.w("OverlayService", "⚠️ FlutterEngine ou DartExecutor nulo, não foi possível chamar onOverlayClosed");
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Falha ao chamar onOverlayClosed", e);
        }

        if (windowManager != null && flutterView != null) {
            try {
                Log.d("OverlayService", "🗑️ Removendo FlutterView do WindowManager");
                windowManager.removeView(flutterView);
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Erro ao remover flutterView", e);
            }
            Log.d("OverlayService", "🔌 Desconectando FlutterView do FlutterEngine");
            flutterView.detachFromFlutterEngine();
            flutterView = null;
            windowManager = null;
        }

        // Clean up animation timers to prevent SIGSEGV and memory leaks
        try {
            if (mTrayAnimationTimer != null) {
                mTrayAnimationTimer.cancel();
                mTrayAnimationTimer.purge();
                mTrayAnimationTimer = null;
            }
            if (mTrayTimerTask != null) {
                mTrayTimerTask.cancel();
                mTrayTimerTask = null;
            }
            // Clear animation handler to prevent memory leaks
            if (mAnimationHandler != null) {
                mAnimationHandler.removeCallbacksAndMessages(null);
            }
            Log.d("OverlayService", "🛑 Animation timers and handlers cleaned up");
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error cleaning up animation timers: " + e.getMessage(), e);
        }
        
        // Otimização: Limpeza completa de recursos
        Log.d("OverlayService", "🧹 Limpando recursos e caches");
        isRunning = false;
        instance = null;
        
        // Garantir que as variáveis sejam resetadas
        windowManager = null;
        flutterView = null;
        
        // Limpar cache de conversões
        int dpCacheSize = dpToPxCache.size();
        int pxCacheSize = pxToDpCache.size();
        dpToPxCache.clear();
        pxToDpCache.clear();
        cachedLayoutParams = null;
        
        Log.d("OverlayService", "📊 Cache limpo - DP cache: " + dpCacheSize + " itens, PX cache: " + pxCacheSize + " itens");
        
        // Clean up FlutterEngine cache if this is the last overlay instance
        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null) {
                // Only clean up if no other overlay is running
                if (!LockScreenOverlayActivity.isRunning) {
                    Log.d("OverlayService", "🧹 Limpando FlutterEngine cache - último overlay");
                    FlutterEngineCache.getInstance().remove(OverlayConstants.CACHED_TAG);
                } else {
                    Log.d("OverlayService", "♻️ Mantendo FlutterEngine cache - LockScreenOverlay ainda ativo");
                }
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error cleaning up FlutterEngine cache: " + e.getMessage(), e);
        }

        try {
            NotificationManager notificationManager = (NotificationManager)
                    getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                Log.d("OverlayService", "🔕 Cancelando notificação");
                notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Erro ao cancelar notificação", e);
        }

        super.onDestroy();
        
        // Clean up receivers to prevent memory leaks
        try {
            if (isReceiverRegistered && screenUnlockReceiver != null) {
                Log.d("OverlayService", "📡 Desregistrando screenUnlockReceiver");
                unregisterReceiver(screenUnlockReceiver);
                screenUnlockReceiver = null;
                isReceiverRegistered = false;
            }
            if (screenReceiver != null) {
                Log.d("OverlayService", "📡 Desregistrando screenReceiver");
                unregisterReceiver(screenReceiver);
                screenReceiver = null;
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error unregistering receivers: " + e.getMessage(), e);
        }
        
        Log.i("OverlayService", "✅ OverlayService destruído com sucesso");
        
        // Notificar que o service foi realmente destruído
        Intent destroyedIntent = new Intent("flutter.overlay.window.OVERLAY_SERVICE_DESTROYED");
        destroyedIntent.setPackage(getPackageName());
        sendBroadcast(destroyedIntent);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Log.i("OverlayService", "🎬 onStartCommand() - Intent recebido (tentativa #" + (isRunning ? "subsequente" : "primeira") + ")");
        Log.d("OverlayService", "🔍 onStartCommand chamado - startId: " + startId + ", flags: " + flags);
        Log.d("OverlayService", "🔍 PONTO A: onStartCommand() INICIADO");
        
        if (intent == null) {
            Log.w("OverlayService", "⚠️ Intent nulo, retornando START_NOT_STICKY");
            Log.d("OverlayService", "🔍 PONTO B: Intent nulo - FALHA");
            return START_NOT_STICKY;
        }
        
        String action = intent.getAction();
        Log.d("OverlayService", "📋 Action do Intent: " + (action != null ? action : "null"));
        Log.d("OverlayService", "📊 Estado antes - isRunning: " + isRunning + ", windowManager: " + (windowManager != null) + ", flutterView: " + (flutterView != null));
        Log.d("OverlayService", "🔍 PONTO B: Intent válido - prosseguindo");

        // 🔹 Se overlay já ativo e ação for apenas trazer para frente
        if (windowManager != null && flutterView != null && "SHOW_OVERLAY_AGAIN".equals(action)) {
            int width = intent.getIntExtra("width", 300);
            int height = intent.getIntExtra("height", 300);
            boolean enableDrag = intent.getBooleanExtra("enableDrag", false);
            resizeOverlay(width, height, enableDrag, null);

            int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
            int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
            int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
            int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;
            moveOverlayInternal(dx, dy, null);
            bringOverlayToFront();
            Log.d("OverlayService", "Overlay já ativo, trazido para frente.");
            return START_STICKY;
        }

        mResources = getApplicationContext().getResources();
        Log.d("OverlayService", "📦 Iniciando initOverlay()");
        Log.d("OverlayService", "🔍 PONTO C: Iniciando initOverlay()");

        initOverlay(intent);
        Log.d("OverlayService", "✅ initOverlay() concluído");
        Log.d("OverlayService", "🔍 PONTO D: initOverlay() concluído");
        
        // Verificar se o overlay foi realmente criado
        Log.d("OverlayService", "📊 Estado final - isRunning: " + isRunning + ", windowManager: " + (windowManager != null) + ", flutterView: " + (flutterView != null));
        Log.d("OverlayService", "🔍 PONTO E: onStartCommand() FINALIZADO");

        return START_STICKY;
    }

    private void initOverlay(Intent intent) {
        Log.d("OverlayService", "🔧 initOverlay() - Iniciando configuração do overlay");
        Log.d("OverlayService", "📊 Estado atual - isRunning: " + isRunning + ", windowManager: " + (windowManager != null) + ", flutterView: " + (flutterView != null));
        
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        
        Log.d("OverlayService", "📐 Parâmetros recebidos - StartX: " + startX + ", StartY: " + startY + ", IsClose: " + isCloseWindow);

        if (isCloseWindow) {
            Log.d("OverlayService", "🚪 Fechando overlay conforme solicitado");
            Log.d("OverlayService", "📊 Estado antes de fechar - isRunning: " + isRunning + ", windowManager: " + (windowManager != null) + ", flutterView: " + (flutterView != null));
            
            if (windowManager != null && flutterView != null) {
                try {
                    Log.d("OverlayService", "🗑️ Removendo FlutterView do WindowManager");
                    windowManager.removeView(flutterView);
                    Log.d("OverlayService", "🔌 Desconectando FlutterView do FlutterEngine");
                    flutterView.detachFromFlutterEngine();
                    windowManager = null;
                    flutterView = null;
                    Log.d("OverlayService", "✅ Recursos limpos com sucesso");
                } catch (Exception e) {
                    Log.e("OverlayService", "❌ Erro ao limpar recursos: " + e.getMessage());
                }
            }
            
            isRunning = false;
            Log.d("OverlayService", "📊 Estado após fechar - isRunning: " + isRunning);
            Log.d("OverlayService", "🛑 Chamando stopSelf()");
            stopSelf();
            return;
        }

        if (windowManager != null && flutterView != null) {
                Log.d("OverlayService", "🔄 Overlay já ativo, removendo anterior");
                windowManager.removeView(flutterView);
                flutterView.detachFromFlutterEngine();
                windowManager = null;
                flutterView = null;
                // NÃO chamar stopSelf() aqui - apenas limpar e continuar
                Log.d("OverlayService", "🧹 Overlay anterior removido, continuando com novo");
        }

        isRunning = true;
        instance = this; // Garantir que instance seja definido
        Log.d("OverlayService", "✅ Marcando overlay como running");
        Log.d("OverlayService", "✅ Instance definido: " + (instance != null));
        Log.d("onStartCommand", "Service started");

        // Verificar FlutterEngine no onStartCommand (não apenas no onCreate)
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine == null || engine.getDartExecutor() == null) {
            Log.e("OverlayService", "❌ FlutterEngine não encontrado no cache ou DartExecutor nulo");
            Log.d("OverlayService", "🔄 Tentando criar FlutterEngine no onStartCommand");
            
            try {
                FlutterEngineGroup engineGroup = new FlutterEngineGroup(this);
                DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                        FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                        "overlayMain");
                
                engine = engineGroup.createAndRunEngine(this, entryPoint);
                if (engine != null && engine.getDartExecutor() != null) {
                    FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, engine);
                    Log.i("OverlayService", "✅ FlutterEngine criada no onStartCommand");
                } else {
                    Log.e("OverlayService", "❌ FlutterEngine criada mas DartExecutor é nulo");
                    return;
                }
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Falha ao criar FlutterEngine: " + e.getMessage());
                return;
            }
        } else {
            Log.d("OverlayService", "✅ FlutterEngine encontrado no cache");
        }
        if (flutterChannel == null && engine != null && engine.getDartExecutor() != null) {
            try {
                flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
                flutterChannel.setMethodCallHandler((call, result) -> {
                    try {
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
                    } catch (Exception e) {
                        Log.e("OverlayService", "❌ Error in method call handler: " + e.getMessage(), e);
                        result.error("METHOD_CALL_ERROR", "Error handling method call", e.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error creating MethodChannel: " + e.getMessage(), e);
            }
        }

        if (overlayMessageChannel == null && engine != null && engine.getDartExecutor() != null) {
            try {
                overlayMessageChannel = new BasicMessageChannel<>(engine.getDartExecutor(),
                        OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
                overlayMessageChannel.setMessageHandler((message, reply) -> {
                    try {
                        if (WindowSetup.messenger != null) {
                            WindowSetup.messenger.send(message);
                        }
                    } catch (Exception e) {
                        Log.e("OverlayService", "❌ Error in message handler: " + e.getMessage(), e);
                    }
                });
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error creating BasicMessageChannel: " + e.getMessage(), e);
            }
        }


            if (flutterView != null) {
                flutterView.detachFromFlutterEngine();
                if (windowManager != null) {
                    windowManager.removeView(flutterView);
                    windowManager = null;
                }
            }

            if (engine.getLifecycleChannel() != null) {
                engine.getLifecycleChannel().appIsResumed();
            }
            
            Log.d("OverlayService", "🎬 Criando FlutterView");
            long startTime = System.currentTimeMillis();
            flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
            long creationTime = System.currentTimeMillis() - startTime;
            Log.i("OverlayService", "✅ FlutterView criada em " + creationTime + "ms");
            
            Log.d("OverlayService", "🔌 Conectando FlutterView ao FlutterEngine");
            flutterView.attachToFlutterEngine(engine);
            flutterView.setFitsSystemWindows(true);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            Log.d("OverlayService", "✅ FlutterView configurada com sucesso");
            flutterView.setBackgroundColor(Color.TRANSPARENT);
            flutterView.setOnTouchListener(this);

            // Define tamanho da tela
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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

            int width = intent.getIntExtra("width", WindowSetup.width);
            int height = intent.getIntExtra("height", screenHeight());
            int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
            int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;
            int layoutWidth = (width == -1999 || width == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(width);
            int layoutHeight = (height == -1999 || height == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(height);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    layoutWidth,
                    layoutHeight,
                    0,
                    -statusBarHeightPx(),
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
            params.gravity = WindowSetup.gravity;

            Log.d("OverlayService", "📱 Adicionando FlutterView ao WindowManager");
            try {
                windowManager.addView(flutterView, params);
                Log.i("OverlayService", "✅ FlutterView adicionada ao WindowManager com sucesso");
                
                Log.d("OverlayService", "🎯 Movendo overlay para posição inicial");
                moveOverlayInternal(dx, dy, null);
                Log.i("OverlayService", "✅ Overlay posicionado com sucesso");
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Erro ao adicionar FlutterView ao WindowManager: " + e.getMessage());
                e.printStackTrace();
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
        if (windowManager != null && flutterView != null) {
            try {
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
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error updating overlay flag: " + e.getMessage(), e);
                if (result != null) {
                    result.success(false);
                }
            }
        } else {
            if (result != null) {
                result.success(false);
            }
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null && flutterView != null) {
            try {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
                params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
                WindowSetup.enableDrag = enableDrag;
                windowManager.updateViewLayout(flutterView, params);
                if (result != null) {
                    result.success(true);
                }
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error resizing overlay: " + e.getMessage(), e);
                if (result != null) {
                    result.success(false);
                }
            }
        } else {
            if (result != null) {
                result.success(false);
            }
        }
    }

    private static boolean moveOverlayInternal(int x, int y, @Nullable MethodChannel.Result result) {
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
                try {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                    params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                    params.y = instance.dpToPx(y);
                    instance.windowManager.updateViewLayout(instance.flutterView, params);

                    if (result != null) result.success(true);
                    return true;
                } catch (Exception e) {
                    Log.e("OverlayService", "❌ Error moving overlay: " + e.getMessage(), e);
                    if (result != null) result.success(false);
                    return false;
                }
            }
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

    // Otimização: Cache de LayoutParams para evitar recriações
    private static WindowManager.LayoutParams cachedLayoutParams;
    
    public static boolean moveOverlay(int x, int y) {
        Log.d("OverlayService", "🎯 moveOverlay() - Movendo overlay para (" + x + ", " + y + ")");
        
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
                // Otimização: Usar cache de LayoutParams
                WindowManager.LayoutParams params = cachedLayoutParams;
                if (params == null) {
                    Log.d("OverlayService", "🔄 Cache MISS - Obtendo LayoutParams do FlutterView");
                    params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                    cachedLayoutParams = params;
                } else {
                    Log.v("OverlayService", "💾 Cache HIT - Reutilizando LayoutParams");
                }
                
                // Otimização: Calcular valores apenas se necessário
                int newX = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                int newY = instance.dpToPx(y);
                
                Log.d("OverlayService", "📐 Posição atual: (" + params.x + ", " + params.y + ") -> Nova: (" + newX + ", " + newY + ")");
                
                // Otimização: Verificar se realmente precisa atualizar
                if (params.x != newX || params.y != newY) {
                    long startTime = System.currentTimeMillis();
                    params.x = newX;
                    params.y = newY;
                    
                    // Otimização: Usar post para operação assíncrona
                    instance.windowManager.updateViewLayout(instance.flutterView, params);
                    long updateTime = System.currentTimeMillis() - startTime;
                    
                    Log.i("OverlayService", "✅ Overlay movido em " + updateTime + "ms");
                } else {
                    Log.d("OverlayService", "⏭️ Posição inalterada, pulando atualização");
                }
                return true;
            } else {
                Log.w("OverlayService", "⚠️ WindowManager nulo");
                return false;
            }
        } else {
            Log.w("OverlayService", "⚠️ Instance ou FlutterView nulos");
            return false;
        }
    }

    /**
     * Check if the app has the necessary permissions to start foreground service
     */
    private boolean hasForegroundServicePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12+, check if we have the special use permission
            return getApplicationContext().checkSelfPermission(
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE") == 
                android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true; // For older versions, assume permission is granted
    }

    @Override
    public void onCreate() { // Get the cached FlutterEngine
        Log.d("OverlayService", "🚀 onCreate() - Iniciando OverlayService");
        
        // Initialize resources early to prevent null pointer exceptions
        mResources = getApplicationContext().getResources();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        registerScreenUnlockReceiver();
        
        // Usar apenas o cache global do Flutter (mais confiável)
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        
        if (flutterEngine == null || flutterEngine.getDartExecutor() == null) {
            Log.i("OverlayService", "🆕 CRIANDO NOVA FLUTTER ENGINE - Cache global vazio ou DartExecutor nulo");
            long startTime = System.currentTimeMillis();
            
            try {
                FlutterEngineGroup engineGroup = new FlutterEngineGroup(this);
                DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                        FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                        "overlayMain");

                flutterEngine = engineGroup.createAndRunEngine(this, entryPoint);
                
                if (flutterEngine != null && flutterEngine.getDartExecutor() != null) {
                    long creationTime = System.currentTimeMillis() - startTime;
                    Log.i("OverlayService", "✅ FlutterEngine criada em " + creationTime + "ms");
                    
                    // Armazenar no cache global
                    FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, flutterEngine);
                    Log.d("OverlayService", "💾 Engine armazenada no cache global");
                } else {
                    Log.e("OverlayService", "❌ FlutterEngine criada mas DartExecutor é nulo");
                    return;
                }
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Falha ao criar FlutterEngine no onCreate: " + e.getMessage(), e);
                return;
            }
        } else {
            Log.i("OverlayService", "♻️ REUTILIZANDO ENGINE do cache global");
        }

        // Create the MethodChannel with the properly initialized FlutterEngine
        if (flutterEngine != null && flutterEngine.getDartExecutor() != null) {
            try {
                flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
                overlayMessageChannel = new BasicMessageChannel(flutterEngine.getDartExecutor(),
                        OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
                
                flutterChannel.setMethodCallHandler((call, result) -> {
                    try {
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
                    } catch (Exception e) {
                        Log.e("OverlayService", "❌ Error in onCreate method call handler: " + e.getMessage(), e);
                        result.error("METHOD_CALL_ERROR", "Error handling method call", e.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error creating channels in onCreate: " + e.getMessage(), e);
            }
        }

        // 🔹 1. Criar canal e notificação rapidamente
        createNotificationChannel();
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

        // Handle foreground service start with proper error handling for Android 12+
        // CRITICAL: startForeground() MUST be called within 5 seconds when service is started with startForegroundService()
        try {
            boolean hasPermission = hasForegroundServicePermission();
            Log.d("OverlayService", "🔐 Foreground service permission check: " + hasPermission);
            
            if (Build.VERSION.SDK_INT >= 34) {
                int foregroundType = 0;
                try {
                    foregroundType = (int) ServiceInfo.class
                            .getField("FOREGROUND_SERVICE_TYPE_SPECIAL_USE").get(null);
                } catch (Exception e) {
                    Log.e("OverlayService", "Failed to get FOREGROUND_SERVICE_TYPE_SPECIAL_USE", e);
                }
                startForeground(OverlayConstants.NOTIFICATION_ID, notification, foregroundType);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ (API 31+), use special use foreground service type
                int foregroundType = 0;
                try {
                    foregroundType = (int) ServiceInfo.class
                            .getField("FOREGROUND_SERVICE_TYPE_SPECIAL_USE").get(null);
                } catch (Exception e) {
                    Log.e("OverlayService", "Failed to get FOREGROUND_SERVICE_TYPE_SPECIAL_USE", e);
                }
                startForeground(OverlayConstants.NOTIFICATION_ID, notification, foregroundType);
            } else {
                startForeground(OverlayConstants.NOTIFICATION_ID, notification);
            }
            
            if (!hasPermission) {
                Log.w("OverlayService", "⚠️ Started foreground service without proper permission - may be unstable");
            } else {
                Log.d("OverlayService", "✅ Foreground service started successfully with proper permissions");
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Failed to start foreground service: " + e.getMessage(), e);
            // CRITICAL: Even if there's an error, we must call startForeground() to avoid timeout
            try {
                Log.w("OverlayService", "🔄 Attempting fallback startForeground() call");
                startForeground(OverlayConstants.NOTIFICATION_ID, notification);
                Log.d("OverlayService", "✅ Fallback startForeground() succeeded");
            } catch (Exception fallbackException) {
                Log.e("OverlayService", "❌ Fallback startForeground() also failed: " + fallbackException.getMessage(), fallbackException);
                // If even the fallback fails, the service will be killed by the system
                Log.e("OverlayService", "💀 Service will be terminated due to startForeground() failure");
            }
        }

        instance = this;
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

    // Otimização: Funções de conversão com cache para melhor performance
    private int dpToPx(int dp) {
        if (mResources == null) {
            Log.e("OverlayService", "Resources is null in dpToPx");
            return dp; // Return original value as fallback
        }
        
        // Verificar cache primeiro
        Integer cached = dpToPxCache.get(dp);
        if (cached != null) {
            Log.v("OverlayService", "💾 Cache HIT - dpToPx(" + dp + ") = " + cached + " (cache size: " + dpToPxCache.size() + ")");
            return cached;
        }
        
        // Calcular e cachear
        long startTime = System.nanoTime();
        int result = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
        long calcTime = (System.nanoTime() - startTime) / 1000; // microsegundos
        
        dpToPxCache.put(dp, result);
        Log.v("OverlayService", "🔄 Cache MISS - dpToPx(" + dp + ") = " + result + " (calc: " + calcTime + "μs, cache size: " + dpToPxCache.size() + ")");
        return result;
    }

    private double pxToDp(int px) {
        if (mResources == null) {
            Log.e("OverlayService", "Resources is null in pxToDp");
            return px; // Return original value as fallback
        }
        
        // Verificar cache primeiro
        Integer cached = pxToDpCache.get(px);
        if (cached != null) {
            Log.v("OverlayService", "💾 Cache HIT - pxToDp(" + px + ") = " + cached + " (cache size: " + pxToDpCache.size() + ")");
            return cached;
        }
        
        // Calcular e cachear
        long startTime = System.nanoTime();
        double result = (double) px / mResources.getDisplayMetrics().density;
        long calcTime = (System.nanoTime() - startTime) / 1000; // microsegundos
        
        pxToDpCache.put(px, (int) result);
        Log.v("OverlayService", "🔄 Cache MISS - pxToDp(" + px + ") = " + result + " (calc: " + calcTime + "μs, cache size: " + pxToDpCache.size() + ")");
        return result;
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
        // Add comprehensive null checks to prevent SIGSEGV
        if (windowManager == null || flutterView == null || !WindowSetup.enableDrag) {
            Log.w("OverlayService", "⚠️ onTouch: Missing required components - windowManager: " + (windowManager != null) + ", flutterView: " + (flutterView != null) + ", enableDrag: " + WindowSetup.enableDrag);
            return false;
        }
        
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            if (params == null) {
                Log.e("OverlayService", "❌ onTouch: LayoutParams is null");
                return false;
            }
            
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
                    boolean invertX = WindowSetup.gravity == (Gravity.TOP | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.CENTER | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    boolean invertY = WindowSetup.gravity == (Gravity.BOTTOM | Gravity.LEFT)
                            || WindowSetup.gravity == Gravity.BOTTOM
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    int xx = params.x + ((int) dx * (invertX ? -1 : 1));
                    int yy = params.y + ((int) dy * (invertY ? -1 : 1));
                    params.x = xx;
                    params.y = yy;
                    
                    // Add null check before WindowManager operation
                    if (windowManager != null && flutterView != null) {
                        try {
                            windowManager.updateViewLayout(flutterView, params);
                        } catch (Exception e) {
                            Log.e("OverlayService", "❌ Error updating view layout: " + e.getMessage(), e);
                        }
                    }
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (!WindowSetup.positionGravity.equals("none")) {
                        if (windowManager == null || flutterView == null) {
                            Log.w("OverlayService", "⚠️ onTouch: Cannot start animation - missing components");
                            return false;
                        }
                        
                        try {
                            windowManager.updateViewLayout(flutterView, params);
                        } catch (Exception e) {
                            Log.e("OverlayService", "❌ Error updating view layout in animation: " + e.getMessage(), e);
                        }
                        
                        // Safely create animation timer
                        try {
                            // Cancel existing timer to prevent memory leaks
                            if (mTrayAnimationTimer != null) {
                                mTrayAnimationTimer.cancel();
                                mTrayAnimationTimer.purge();
                                mTrayAnimationTimer = null;
                            }
                            if (mTrayTimerTask != null) {
                                mTrayTimerTask.cancel();
                                mTrayTimerTask = null;
                            }
                            
                            mTrayTimerTask = new TrayAnimationTimerTask();
                            mTrayAnimationTimer = new Timer("OverlayAnimationTimer", true); // Use daemon thread
                            mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                        } catch (Exception e) {
                            Log.e("OverlayService", "❌ Error creating animation timer: " + e.getMessage(), e);
                        }
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Critical error in onTouch: " + e.getMessage(), e);
            return false;
        }
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params;
        private volatile boolean isCancelled = false;

        public TrayAnimationTimerTask() {
            super();
            try {
                if (flutterView == null || windowManager == null) {
                    Log.w("OverlayService", "⚠️ TrayAnimationTimerTask: Missing components");
                    isCancelled = true;
                    return;
                }
                
                params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                if (params == null) {
                    Log.e("OverlayService", "❌ TrayAnimationTimerTask: LayoutParams is null");
                    isCancelled = true;
                    return;
                }
                
                mDestY = lastYPosition;
                switch (WindowSetup.positionGravity) {
                    case "auto":
                        mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0
                                : szWindow.x - flutterView.getWidth();
                        break;
                    case "left":
                        mDestX = 0;
                        break;
                    case "right":
                        mDestX = szWindow.x - flutterView.getWidth();
                        break;
                    default:
                        mDestX = params.x;
                        mDestY = params.y;
                        break;
                }
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error in TrayAnimationTimerTask constructor: " + e.getMessage(), e);
                isCancelled = true;
            }
        }

        @Override
        public void run() {
            if (isCancelled) {
                return;
            }
            
            try {
                mAnimationHandler.post(() -> {
                    try {
                        // Comprehensive null checks to prevent SIGSEGV
                        if (isCancelled || params == null || flutterView == null || windowManager == null) {
                            Log.d("OverlayService", "🛑 Animation cancelled or missing components");
                            return;
                        }
                        
                        // Check if the view is still attached
                        if (flutterView.getParent() == null) {
                            Log.w("OverlayService", "⚠️ FlutterView is not attached, cancelling animation");
                            cancel();
                            return;
                        }
                        
                        params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                        params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                        
                        try {
                            windowManager.updateViewLayout(flutterView, params);
                        } catch (Exception e) {
                            Log.e("OverlayService", "❌ Error updating view layout in animation: " + e.getMessage(), e);
                            cancel();
                            return;
                        }
                        
                        if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                            TrayAnimationTimerTask.this.cancel();
                            if (mTrayAnimationTimer != null) {
                                mTrayAnimationTimer.cancel();
                                mTrayAnimationTimer.purge();
                                mTrayAnimationTimer = null;
                            }
                        }
                    } catch (Exception e) {
                        Log.e("OverlayService", "❌ Error in animation handler: " + e.getMessage(), e);
                        cancel();
                    }
                });
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error posting animation to handler: " + e.getMessage(), e);
                cancel();
            }
        }
        
        @Override
        public boolean cancel() {
            isCancelled = true;
            return super.cancel();
        }
    }
    private void bringOverlayToFront() {
        if (flutterView == null || windowManager == null) {
            Log.w("OverlayService", "⚠️ bringOverlayToFront: Missing components");
            return;
        }
        
        if (flutterView.getParent() == null) {
            Log.w("OverlayService", "⚠️ FlutterView is not attached to WindowManager");
            return;
        }
        
        try {
            WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            if (layoutParams == null) {
                Log.e("OverlayService", "❌ LayoutParams is null in bringOverlayToFront");
                return;
            }
            
            windowManager.removeView(flutterView);
            windowManager.addView(flutterView, layoutParams);
            Log.d("OverlayService", "✅ Overlay brought to front successfully");
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error bringing overlay to front: " + e.getMessage(), e);
        }
    }

}

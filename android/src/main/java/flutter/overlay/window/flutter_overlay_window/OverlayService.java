package flutter.overlay.window.flutter_overlay_window;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
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
import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Locale;
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
    private FlutterEngine engine; // ✅ Armazenar engine como variável de instância
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
    
    // ✅ WakeLock para manter o serviço ativo mesmo com tela bloqueada
    private PowerManager powerManager;
    private WakeLock wakeLock;
    private static final long WAKE_LOCK_FAILURE_WINDOW_MS = 10000L;
    private static final int WAKE_LOCK_FAILURE_THRESHOLD = 3;
    private static final long WAKE_LOCK_RESTRICTION_PERSIST_MS = 15 * 60 * 1000L; // 15 minutos
    private static final long WAKE_LOCK_RESTRICTION_LOG_WINDOW_MS = 10000L;
    private static final String PREFS_NAME = "flutter_overlay_window";
    private static final String PREF_WAKE_LOCK_TS = "wake_lock_restriction_ts";
    private static final String PREF_WAKE_LOCK_REASON = "wake_lock_restriction_reason";
    private static volatile long lastWakeLockStableTimestamp = 0L;
    private static volatile long lastWakeLockFailureTimestamp = 0L;
    private static volatile int wakeLockFailureCount = 0;
    private static volatile boolean wakeLockRestrictedBySystem = false;
    private static volatile long wakeLockRestrictionDetectedAt = 0L;
    private static volatile String wakeLockRestrictionReason = null;
    private static volatile long lastWakeLockWarningTimestamp = 0L;

    private BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // ✅ Verificar se o service ainda está rodando
                if (!isRunning) {
                    Log.w("OverlayService", "⚠️ Service não está rodando, ignorando screenReceiver");
                    return;
                }
                
                try {
                    FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
                    if (flutterEngine == null || flutterEngine.getDartExecutor() == null) {
                        Log.w("OverlayService", "⚠️ FlutterEngine or DartExecutor is null in screenReceiver");
                        return;
                    }
                    
                    // ✅ Verificar se o DartExecutor ainda está executando
                    if (flutterEngine.getDartExecutor().isExecutingDart()) {
                        if (flutterEngine.getLifecycleChannel() != null) {
                            flutterEngine.getLifecycleChannel().appIsResumed();
                        }
                    } else {
                        Log.w("OverlayService", "⚠️ DartExecutor não está mais executando Dart");
                    }
                } catch (IllegalStateException e) {
                    Log.e("OverlayService", "❌ Engine em estado inválido no screenReceiver: " + e.getMessage());
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
                    // ✅ Verificar se o service ainda está rodando
                    if (!isRunning) {
                        Log.w("OverlayService", "⚠️ Service não está rodando, ignorando screenUnlockReceiver");
                        return;
                    }
                    
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
                                    // ✅ Verificar se o DartExecutor ainda está executando
                                    if (flutterEngine.getDartExecutor().isExecutingDart()) {
                                        flutterView.attachToFlutterEngine(flutterEngine);
                                        if (flutterEngine.getLifecycleChannel() != null) {
                                            flutterEngine.getLifecycleChannel().appIsResumed();
                                        }
                                        flutterView.invalidate();
                                        flutterView.requestLayout();
                                        bringOverlayToFront();
                                        Log.d("OverlayService", "FlutterView resumido e redraw feito.");
                                    } else {
                                        Log.w("OverlayService", "⚠️ DartExecutor não está mais executando Dart");
                                    }
                                } catch (IllegalStateException e) {
                                    Log.e("OverlayService", "❌ Engine em estado inválido: " + e.getMessage());
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
        
        // ✅ Liberar WakeLock se estiver ativo
        releaseWakeLock();

        // ✅ Verificações de segurança para evitar crash nativo
        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
            if (engine != null && engine.getDartExecutor() != null) {
                // ✅ Verificar se o DartExecutor ainda está executando
                if (engine.getDartExecutor().isExecutingDart()) {
                    Log.d("OverlayService", "📞 Chamando onOverlayClosed no Flutter");
                    new MethodChannel(engine.getDartExecutor(), "my_custom_overlay_channel")
                            .invokeMethod("onOverlayClosed", null);
                } else {
                    Log.w("OverlayService", "⚠️ DartExecutor não está mais executando Dart");
                }
            } else {
                Log.w("OverlayService", "⚠️ FlutterEngine ou DartExecutor nulo, não foi possível chamar onOverlayClosed");
            }
        } catch (IllegalStateException e) {
            Log.e("OverlayService", "❌ Engine em estado inválido: " + e.getMessage());
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Falha ao chamar onOverlayClosed", e);
        }

        if (windowManager != null && flutterView != null) {
            try {
                // ✅ Verificar se a view ainda está anexada antes de remover
                if (flutterView.getParent() != null) {
                    Log.d("OverlayService", "🗑️ Removendo FlutterView do WindowManager");
                    windowManager.removeView(flutterView);
                } else {
                    Log.w("OverlayService", "⚠️ FlutterView já foi removida do WindowManager");
                }
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Erro ao remover flutterView", e);
            }
            
            try {
                Log.d("OverlayService", "🔌 Desconectando FlutterView do FlutterEngine");
                flutterView.detachFromFlutterEngine();
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Erro ao desconectar FlutterView", e);
            }
            
            flutterView = null;
            windowManager = null;
        }

        // ✅ Clean up animation timers to prevent SIGSEGV and memory leaks
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
            // ✅ Clear animation handler to prevent memory leaks
            if (mAnimationHandler != null) {
                mAnimationHandler.removeCallbacksAndMessages(null);
                mAnimationHandler = null;
            }
            Log.d("OverlayService", "🛑 Animation timers and handlers cleaned up");
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error cleaning up animation timers: " + e.getMessage(), e);
        }
        
        // ✅ Clean up notification monitoring
        try {
            if (notificationMonitorTimer != null) {
                notificationMonitorTimer.cancel();
                notificationMonitorTimer.purge();
                notificationMonitorTimer = null;
            }
            if (notificationMonitorTask != null) {
                notificationMonitorTask.cancel();
                notificationMonitorTask = null;
            }
            Log.d("OverlayService", "🛑 Notification monitoring cleaned up");
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error cleaning up notification monitoring: " + e.getMessage(), e);
        }
        
        // ✅ Otimização: Limpeza completa de recursos
        Log.d("OverlayService", "🧹 Limpando recursos e caches");
        isRunning = false;
        instance = null;
        
        // ✅ Garantir que as variáveis sejam resetadas
        windowManager = null;
        flutterView = null;
        flutterChannel = null;
        overlayMessageChannel = null;
        
        // ✅ Limpar cache de conversões
        int dpCacheSize = dpToPxCache.size();
        int pxCacheSize = pxToDpCache.size();
        dpToPxCache.clear();
        pxToDpCache.clear();
        cachedLayoutParams = null;
        
        // ✅ Reset de variáveis de estado
        mStatusBarHeight = -1;
        mNavigationBarHeight = -1;
        mResources = null;
        
        Log.d("OverlayService", "📊 Cache limpo - DP cache: " + dpCacheSize + " itens, PX cache: " + pxCacheSize + " itens");
        
        // ✅ NUNCA limpar o FlutterEngine cache - sempre reutilizar!
        // O cache deve ser mantido para reutilização entre overlays
        Log.d("OverlayService", "♻️ Mantendo FlutterEngine cache para reutilização");
        
        // ✅ Limpar referência da instância para evitar memory leaks
        this.engine = null;

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
        
        // ✅ Clean up receivers to prevent memory leaks
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
        
        // ✅ Clean up all handlers and timers
        try {
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
                handler = null;
            }
            if (mAnimationHandler != null) {
                mAnimationHandler.removeCallbacksAndMessages(null);
                mAnimationHandler = null;
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error cleaning up handlers: " + e.getMessage(), e);
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
            // ✅ Corrigir lógica de posicionamento - converter DP para pixels
            int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : dpToPx(startX);
            int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : dpToPx(startY);
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
        
        // ✅ Garantir que o WakeLock está ativo após iniciar o overlay
        ensureWakeLock();
        
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

        // ✅ Verificar FlutterEngine no onStartCommand (não apenas no onCreate)
        // ✅ Usar apenas a engine criada no onCreate()
        FlutterEngine engine = this.engine;
        if (engine == null || engine.getDartExecutor() == null) {
            Log.e("OverlayService", "❌ FlutterEngine não disponível - onCreate() não foi chamado ou falhou");
            return;
        }
        
        
        // ✅ Verificar se o DartExecutor ainda está executando
        if (!engine.getDartExecutor().isExecutingDart()) {
            Log.w("OverlayService", "⚠️ DartExecutor não está mais executando Dart");
            return;
        }
        
        Log.d("OverlayService", "♻️ Reutilizando FlutterEngine do onCreate()");
        if (flutterChannel == null && engine != null && engine.getDartExecutor() != null) {
            try {
                
                flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
                flutterChannel.setMethodCallHandler((call, result) -> {
                    try {
                        // ✅ Verificar se o service ainda está rodando
                        if (!isRunning) {
                            Log.w("OverlayService", "⚠️ Service não está rodando, ignorando chamada: " + call.method);
                            result.error("SERVICE_NOT_RUNNING", "Service is not running", null);
                            return;
                        }
                        
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
                        // ✅ Verificar se o service ainda está rodando
                        if (!isRunning) {
                            Log.w("OverlayService", "⚠️ Service não está rodando, ignorando mensagem");
                            reply.reply(null);
                            return;
                        }
                        
                        WindowSetup.sendMessage(message);
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
            // ✅ Corrigir lógica de posicionamento - converter DP para pixels
            int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : dpToPx(startX);
            int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : dpToPx(startY);
            
            Log.d("OverlayService", "🎯 Posicionamento - startX: " + startX + " -> dx: " + dx + ", startY: " + startY + " -> dy: " + dy);
            int layoutWidth = (width == -1999 || width == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(width);
            int layoutHeight = (height == -1999 || height == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(height);

            // ✅ Corrigir: Usar posições calculadas em vez de valores fixos
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    layoutWidth,
                    layoutHeight,
                    dx,  // ✅ Usar posição X calculada
                    dy,  // ✅ Usar posição Y calculada
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
            
            // ✅ Corrigir: Usar Gravity.TOP | Gravity.LEFT para posicionamento absoluto
            params.gravity = Gravity.TOP | Gravity.RIGHT;
            
            Log.d("OverlayService", "🎯 LayoutParams - x: " + params.x + ", y: " + params.y + ", gravity: " + params.gravity);

            Log.d("OverlayService", "📱 Adicionando FlutterView ao WindowManager");
            try {
                windowManager.addView(flutterView, params);
                Log.i("OverlayService", "✅ FlutterView adicionada ao WindowManager com sucesso");
                Log.i("OverlayService", "✅ Overlay posicionado com sucesso em (" + dx + ", " + dy + ")");
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
                // ✅ Surface State Validation
                if (!isSurfaceValid()) {
                    Log.w("OverlayService", "⚠️ Surface not valid, skipping flag update");
                    if (result != null) result.success(false);
                    return;
                }
                
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

                // ✅ Thread Synchronization with Accessibility Support
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        // ✅ Wait for accessibility operations to complete
                        if (isAccessibilityActive()) {
                            Thread.sleep(100); // Give accessibility time to complete
                        }
                        
                        windowManager.updateViewLayout(flutterView, params);
                        if (result != null) result.success(true);
                    } catch (Exception e) {
                        Log.e("OverlayService", "❌ Error updating overlay flag: " + e.getMessage(), e);
                        if (result != null) result.success(false);
                    }
                });
                
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
                // ✅ Surface State Validation
                if (!isSurfaceValid()) {
                    Log.w("OverlayService", "⚠️ Surface not valid, skipping resize");
                    if (result != null) result.success(false);
                    return;
                }
                
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
                params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
                WindowSetup.enableDrag = enableDrag;
                
                // ✅ Thread Synchronization with Accessibility Support
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        // ✅ Wait for accessibility operations to complete
                        if (isAccessibilityActive()) {
                            Thread.sleep(100); // Give accessibility time to complete
                        }
                        
                        windowManager.updateViewLayout(flutterView, params);
                        if (result != null) result.success(true);
                    } catch (Exception e) {
                        Log.e("OverlayService", "❌ Error resizing overlay: " + e.getMessage(), e);
                        if (result != null) result.success(false);
                    }
                });
                
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Error resizing overlay: " + e.getMessage(), e);
                if (result != null) result.success(false);
            }
        } else {
            if (result != null) result.success(false);
        }
    }

    private static boolean moveOverlayInternal(int x, int y, @Nullable MethodChannel.Result result) {
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
                if (!instance.isSurfaceValid()) {
                    Log.w("OverlayService", "⚠️ moveOverlay: surface not valid, skipping");
                    if (result != null) {
                        result.success(false);
                    }
                    return false;
                }
                try {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                    // ✅ Corrigir: x e y já estão em pixels, não converter novamente
                    params.x = (x == -1999 || x == -1) ? -1 : x;
                    params.y = y;
                    
                    Log.d("OverlayService", "🎯 moveOverlayInternal - x: " + x + ", y: " + y + " -> params.x: " + params.x + ", params.y: " + params.y);
                    
                    // ✅ Thread Synchronization with Accessibility Support
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            // ✅ Wait for accessibility operations to complete
                            if (instance.isAccessibilityActive()) {
                                Thread.sleep(100); // Give accessibility time to complete
                            }
                            
                            instance.windowManager.updateViewLayout(instance.flutterView, params);
                            if (result != null) result.success(true);
                        } catch (Exception e) {
                            Log.e("OverlayService", "❌ Error moving overlay: " + e.getMessage(), e);
                            if (result != null) result.success(false);
                        }
                    });
                    
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
            boolean hasBasePermission = getApplicationContext().checkSelfPermission(
                "android.permission.FOREGROUND_SERVICE") == 
                android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasLocationPermission = true;
            if (Build.VERSION.SDK_INT >= 34) {
                hasLocationPermission = getApplicationContext().checkSelfPermission(
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            
            Log.d("OverlayService", "🔐 Permission check - FOREGROUND_SERVICE: " + hasBasePermission + ", FOREGROUND_SERVICE_LOCATION: " + hasLocationPermission);
            
            return hasBasePermission && hasLocationPermission;
        }
        return true; // For older versions, assume permission is granted
    }

    @Override
    public void onCreate() { // Get the cached FlutterEngine
        Log.d("OverlayService", "🚀 onCreate() - Iniciando OverlayService");
        Log.d("OverlayService", "🔍 onCreate() chamado - Engine count atual: " + (engine != null ? "Engine já existe" : "Engine nula"));
        
        
        // Initialize resources early to prevent null pointer exceptions
        mResources = getApplicationContext().getResources();
        
        // ✅ Adquirir WakeLock para manter o serviço ativo mesmo com tela bloqueada
        acquireWakeLock();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        registerScreenUnlockReceiver();
        
        // ✅ Verificar se já temos uma engine na instância
        if (this.engine != null && this.engine.getDartExecutor() != null) {
            Log.i("OverlayService", "♻️ Engine já existe na instância - reutilizando");
            return;
        }
        
        
        // ✅ Verificar se já existe uma instância do service
        if (instance != null && instance != this) {
            Log.i("OverlayService", "♻️ Service já existe - reutilizando instância existente");
            this.engine = instance.engine;
            return;
        }
        
        // Usar apenas o cache global do Flutter (mais confiável)
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        
        // ✅ Debug: Log do engine count
        Log.d("OverlayService", "🔍 Verificando FlutterEngine no cache");
        
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
                    
                    // ✅ Armazenar na variável de instância
                    this.engine = flutterEngine;
                    
                    // ✅ Debug: Log do engine count após criação
                    Log.d("OverlayService", "🔍 FlutterEngine criada e armazenada no cache");
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
            // ✅ Armazenar na variável de instância
            this.engine = flutterEngine;
            // ✅ Debug: Log do engine count ao reutilizar
            Log.d("OverlayService", "🔍 Reutilizando FlutterEngine do cache");
        }

        // ✅ Create the MethodChannel with the properly initialized FlutterEngine
        if (flutterEngine != null && flutterEngine.getDartExecutor() != null) {
            try {
                
                flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
                overlayMessageChannel = new BasicMessageChannel(flutterEngine.getDartExecutor(),
                        OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
                
                flutterChannel.setMethodCallHandler((call, result) -> {
                    try {
                        // ✅ Verificar se o service ainda está rodando
                        if (!isRunning) {
                            Log.w("OverlayService", "⚠️ Service não está rodando, ignorando chamada: " + call.method);
                            result.error("SERVICE_NOT_RUNNING", "Service is not running", null);
                            return;
                        }
                        
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
        
        // ✅ Usar NotificationCompat.Builder com todas as configurações de persistência
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .setOngoing(true) // ✅ Evento em andamento
                .setAutoCancel(false) // ✅ Não permite fechar ao tocar
                .setSound(null)
                .setVibrate(new long[]{0L})
                .setPriority(NotificationCompat.PRIORITY_LOW) // ✅ Prioridade baixa para não ser intrusiva
                .setCategory(NotificationCompat.CATEGORY_SERVICE) // ✅ Categoria de serviço
                .setShowWhen(false) // ✅ Não mostrar timestamp
                .setLocalOnly(true); // ✅ Apenas local, não sincronizar
        
        // ✅ Aplicar flags de persistência usando NotificationCompat
        builder.setOngoing(true);
        builder.setAutoCancel(false);
        
        Notification notification = builder.build();
        
        // ✅ Aplicar flags adicionais para máxima persistência
        notification.flags |= Notification.FLAG_NO_CLEAR; // Não pode ser limpa pelo usuário
        notification.flags |= Notification.FLAG_ONGOING_EVENT; // Evento em andamento
        notification.flags |= Notification.FLAG_FOREGROUND_SERVICE; // Serviço em primeiro plano
        notification.flags |= Notification.FLAG_INSISTENT; // ✅ Insistente - não pode ser removida

        boolean hasPermission = hasForegroundServicePermission();
        Log.d("OverlayService", "🔐 Foreground service permission check: " + hasPermission);

        boolean startedForeground = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startForeground(OverlayConstants.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
                startedForeground = true;
                Log.d("OverlayService", "✅ startForeground() invoked with MANIFEST type");
            } catch (SecurityException se) {
                Log.w("OverlayService", "⚠️ startForeground with MANIFEST type rejected, retrying without explicit type", se);
                try {
                    startForeground(OverlayConstants.NOTIFICATION_ID, notification);
                    startedForeground = true;
                    Log.d("OverlayService", "✅ startForeground() fallback without type succeeded");
                } catch (Exception inner) {
                    Log.e("OverlayService", "❌ Fallback startForeground() without type failed", inner);
                }
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Failed to start foreground service with MANIFEST type", e);
                try {
                    startForeground(OverlayConstants.NOTIFICATION_ID, notification);
                    startedForeground = true;
                    Log.d("OverlayService", "✅ startForeground() fallback without type succeeded");
                } catch (Exception inner) {
                    Log.e("OverlayService", "❌ Fallback startForeground() without type failed", inner);
                }
            }
        } else {
            try {
                startForeground(OverlayConstants.NOTIFICATION_ID, notification);
                startedForeground = true;
                Log.d("OverlayService", "✅ startForeground() invoked (pre-Android 12)");
            } catch (Exception e) {
                Log.e("OverlayService", "❌ Failed to start foreground service", e);
            }
        }

        if (!startedForeground) {
            Log.e("OverlayService", "💀 Service will be terminated because startForeground() could not be invoked successfully");
        } else if (!hasPermission) {
            Log.w("OverlayService", "⚠️ Started foreground service but FOREGROUND_SERVICE permission check returned false");
        } else {
            Log.d("OverlayService", "✅ Foreground service started successfully");
            startNotificationMonitoring();
        }

        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ✅ Configurar canal para ser persistente e não fechável
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_LOW); // ✅ IMPORTANCE_LOW para não ser intrusiva
            
            // ✅ Configurações para tornar o canal persistente
            serviceChannel.setSound(null, null); 
            serviceChannel.enableVibration(false);
            serviceChannel.setShowBadge(false); // ✅ Não mostrar badge
            serviceChannel.setBypassDnd(false); // ✅ Não contornar "Não perturbe"
            serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE); // ✅ Visibilidade na tela de bloqueio
            
            // ✅ Para Android 8.0+ (API 26+), configurar para ser persistente
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                serviceChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
                serviceChannel.enableLights(false);
                serviceChannel.enableVibration(false);
            }
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d("OverlayService", "✅ Canal de notificação criado com configurações persistentes");
            }
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(name, resType,
                getApplicationContext().getPackageName());
    }

    // ✅ Monitoramento da notificação para recriar se removida
    private Timer notificationMonitorTimer;
    private TimerTask notificationMonitorTask;
    
    private void startNotificationMonitoring() {
        try {
            // Cancelar monitoramento anterior se existir
            if (notificationMonitorTimer != null) {
                notificationMonitorTimer.cancel();
                notificationMonitorTimer = null;
            }
            if (notificationMonitorTask != null) {
                notificationMonitorTask.cancel();
                notificationMonitorTask = null;
            }
            
            notificationMonitorTask = new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (!isRunning) {
                            return; // Service não está rodando, parar monitoramento
                        }
                        
                        // Verificar se a notificação ainda existe
                        NotificationManager notificationManager = (NotificationManager) 
                            getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
                        
                        if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Verificar se a notificação ainda está ativa
                            boolean notificationExists = false;
                            try {
                                StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
                                for (StatusBarNotification notification : notifications) {
                                    if (notification.getId() == OverlayConstants.NOTIFICATION_ID) {
                                        notificationExists = true;
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                Log.w("OverlayService", "⚠️ Error checking notification status: " + e.getMessage());
                            }
                            
                            // Se a notificação foi removida, recriar
                            if (!notificationExists) {
                                Log.w("OverlayService", "⚠️ Notification was removed, recreating...");
                                handler.post(() -> {
                                    try {
                                        recreateNotification();
                                    } catch (Exception e) {
                                        Log.e("OverlayService", "❌ Error recreating notification: " + e.getMessage());
                                    }
                                });
                            }
                        }
                        
                        // ✅ Verificar e garantir que o WakeLock está ativo
                        // Isso é crítico para manter o serviço rodando e enviando localização
                        // mesmo após alguns minutos com a tela bloqueada
                        handler.post(() -> {
                            try {
                                ensureWakeLock();
                            } catch (Exception e) {
                                Log.e("OverlayService", "❌ Error ensuring WakeLock: " + e.getMessage());
                            }
                        });
                    } catch (Exception e) {
                        Log.e("OverlayService", "❌ Error in notification monitoring: " + e.getMessage());
                    }
                }
            };
            
            notificationMonitorTimer = new Timer("NotificationMonitor", true);
            notificationMonitorTimer.schedule(notificationMonitorTask, 5000, 2000); // Verificar a cada 2 segundos
            Log.d("OverlayService", "✅ Notification monitoring started");
            
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error starting notification monitoring: " + e.getMessage());
        }
    }
    
    private void recreateNotification() {
        try {
            Log.d("OverlayService", "🔄 Recreating notification...");
            
            // Recriar a notificação com as mesmas configurações
            Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

            int notifyIcon = getDrawableResourceId("mipmap", "ic_launcher_notification");
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                    .setContentTitle(WindowSetup.overlayTitle)
                    .setContentText(WindowSetup.overlayContent)
                    .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                    .setContentIntent(pendingIntent)
                    .setVisibility(WindowSetup.notificationVisibility)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setSound(null)
                    .setVibrate(new long[]{0L})
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setShowWhen(false)
                    .setLocalOnly(true);
            
            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_NO_CLEAR;
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
            notification.flags |= Notification.FLAG_INSISTENT;
            
            // Recriar a notificação
            startForeground(OverlayConstants.NOTIFICATION_ID, notification);
            Log.d("OverlayService", "✅ Notification recreated successfully");
            
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Error recreating notification: " + e.getMessage());
        }
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

    // ✅ Surface State Validation - View-based (no internal surface APIs)
    private boolean isSurfaceValid() {
        try {
            if (flutterView == null) {
                return false;
            }

            // If accessibility is active, use the more conservative check
            if (isAccessibilityActive()) {
                Log.d("OverlayService", "🔍 Accessibility active, using safe surface check");
                return isSurfaceValidWithAccessibility();
            }

            // View is attached to window (API < 19 lacks isAttachedToWindow)
            boolean attached = Build.VERSION.SDK_INT < 19 || flutterView.isAttachedToWindow();
            // Has a window token
            boolean hasToken = flutterView.getWindowToken() != null;
            // Has been laid out (non-zero size)
            boolean laidOut = flutterView.getWidth() > 0 && flutterView.getHeight() > 0;
            // Visible and shown
            boolean visible = flutterView.getVisibility() == View.VISIBLE && flutterView.isShown();

            return attached && hasToken && laidOut && visible;
        } catch (Exception e) {
            Log.w("OverlayService", "⚠️ Error checking surface validity: " + e.getMessage());
            return false;
        }
    }

    // ✅ Accessibility Detection
    private boolean isAccessibilityActive() {
        try {
            android.view.accessibility.AccessibilityManager am = 
                (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled();
        } catch (Exception e) {
            Log.w("OverlayService", "⚠️ Error checking accessibility: " + e.getMessage());
            return false;
        }
    }

    // ✅ Safe Surface Validation with Accessibility
    private boolean isSurfaceValidWithAccessibility() {
        try {
            // Wait a bit for accessibility operations to complete
            Thread.sleep(50);

            if (flutterView == null) {
                return false;
            }

            boolean attached = Build.VERSION.SDK_INT < 19 || flutterView.isAttachedToWindow();
            boolean hasToken = flutterView.getWindowToken() != null;
            boolean laidOut = flutterView.getWidth() > 0 && flutterView.getHeight() > 0;
            boolean visible = flutterView.getVisibility() == View.VISIBLE && flutterView.isShown();

            return attached && hasToken && laidOut && visible;
        } catch (Exception e) {
            Log.w("OverlayService", "⚠️ Error in accessibility surface check: " + e.getMessage());
            return false;
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

    /**
     * Adquire um WakeLock parcial para manter o CPU ativo mesmo com a tela bloqueada
     * PARTIAL_WAKE_LOCK mantém o CPU ativo SEM manter a tela ligada
     * Isso é essencial para manter o serviço rodando e enviando localização continuamente
     * mesmo quando a tela está desligada/bloqueada
     */
    private void acquireWakeLock() {
        try {
            if (powerManager == null) {
                powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            }
            
            if (powerManager != null) {
                // Verificar se o WakeLock já está ativo
                if (wakeLock != null && wakeLock.isHeld()) {
                    Log.d("OverlayService", "ℹ️ WakeLock já está ativo");
                    return;
                }
                
                // Liberar WakeLock anterior se existir mas não estiver ativo
                if (wakeLock != null) {
                    try {
                        wakeLock.release();
                    } catch (Exception e) {
                        // Ignorar se já foi liberado
                    }
                    wakeLock = null;
                }
                
                // PARTIAL_WAKE_LOCK mantém o CPU ativo mesmo com tela bloqueada/desligada
                // Isso permite que o serviço continue enviando localização em background
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "FlutterOverlayWindow::LocationWakeLock"
                );
                wakeLock.setReferenceCounted(false);
                
                if (wakeLock != null) {
                    // Adquirir o WakeLock sem timeout (0 = nunca expira até ser liberado manualmente)
                    wakeLock.acquire(0);
                    lastWakeLockStableTimestamp = SystemClock.elapsedRealtime();
                    wakeLockRestrictedBySystem = false;
                    wakeLockFailureCount = 0;
                    Log.d("OverlayService", "✅ WakeLock adquirido - CPU será mantido ativo mesmo com tela desligada");
                } else {
                    Log.w("OverlayService", "⚠️ Falha ao criar WakeLock");
                }
            } else {
                Log.w("OverlayService", "⚠️ PowerManager é nulo, não é possível adquirir WakeLock");
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Erro ao adquirir WakeLock: " + e.getMessage(), e);
        }
    }
    
    /**
     * Verifica e re-adquire o WakeLock se necessário
     * Útil para garantir que o WakeLock continue ativo mesmo após eventos do sistema
     */
    private void ensureWakeLock() {
        try {
            if (!isRunning) {
                return;
            }
            if (wakeLock == null || !wakeLock.isHeld()) {
                long now = SystemClock.elapsedRealtime();

                if (wakeLockRestrictedBySystem && (now - wakeLockRestrictionDetectedAt) < WAKE_LOCK_RESTRICTION_PERSIST_MS) {
                    if (now - lastWakeLockWarningTimestamp > WAKE_LOCK_RESTRICTION_LOG_WINDOW_MS) {
                    Log.d("OverlayService", "ℹ️ WakeLock ainda sendo derrubado pelo sistema, mantendo flag de restrição");
                        lastWakeLockWarningTimestamp = now;
                    }
                    releaseWakeLock();
                    acquireWakeLock();
                    return;
                }

                if (lastWakeLockStableTimestamp > 0 && (now - lastWakeLockStableTimestamp) < WAKE_LOCK_FAILURE_WINDOW_MS) {
                    wakeLockFailureCount++;
                } else {
                    wakeLockFailureCount = 1;
                }
                lastWakeLockFailureTimestamp = now;

                boolean likelyMiui = isDeviceLikelyXiaomi();
                if (wakeLockFailureCount >= WAKE_LOCK_FAILURE_THRESHOLD || (likelyMiui && wakeLockFailureCount >= 1)) {
                    markWakeLockRestriction(likelyMiui ? "miui.forced_release" : "system.restricted_wakelock");
                }

                if (now - lastWakeLockWarningTimestamp > WAKE_LOCK_RESTRICTION_LOG_WINDOW_MS) {
                    Log.w("OverlayService", "⚠️ WakeLock não está ativo, re-adquirindo...");
                    lastWakeLockWarningTimestamp = now;
                } else if (!wakeLockRestrictedBySystem) {
                    Log.d("OverlayService", "ℹ️ Re-adquirindo WakeLock (controle MIUI)");
                }
                releaseWakeLock();
                acquireWakeLock();
            }
        } catch (SecurityException se) {
            markWakeLockRestriction("security_exception");
            Log.e("OverlayService", "❌ Permissão de WakeLock negada pelo sistema", se);
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Erro ao verificar WakeLock: " + e.getMessage(), e);
        }
    }

    /**
     * Libera o WakeLock quando o serviço é destruído
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null) {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                    Log.d("OverlayService", "✅ WakeLock liberado");
                }
                wakeLock = null;
            }
        } catch (Exception e) {
            Log.e("OverlayService", "❌ Erro ao liberar WakeLock: " + e.getMessage(), e);
        }
    }

    private void markWakeLockRestriction(String reason) {
        if (!isRunning) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (wakeLockRestrictedBySystem && (now - wakeLockRestrictionDetectedAt) < 2000L) {
            return;
        }
        wakeLockRestrictedBySystem = true;
        wakeLockRestrictionDetectedAt = now;
        wakeLockRestrictionReason = reason;
        persistWakeLockRestriction(reason);
    }

    private void persistWakeLockRestriction(String reason) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putLong(PREF_WAKE_LOCK_TS, System.currentTimeMillis())
                    .putString(PREF_WAKE_LOCK_REASON, reason)
                    .apply();
        } catch (Exception e) {
            Log.w("OverlayService", "⚠️ Unable to persist wake lock restriction state: " + e.getMessage());
        }
    }

    public static boolean wasWakeLockRestrictedRecently(Context context) {
        if (context == null) {
            return false;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long ts = prefs.getLong(PREF_WAKE_LOCK_TS, 0L);
            if (ts <= 0) {
                return false;
            }
            return (System.currentTimeMillis() - ts) < WAKE_LOCK_RESTRICTION_PERSIST_MS;
        } catch (Exception e) {
            Log.w("OverlayService", "⚠️ Unable to read wake lock restriction cache: " + e.getMessage());
            return false;
        }
    }

    public static boolean isWakeLockRestrictedBySystem() {
        if (!wakeLockRestrictedBySystem) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - wakeLockRestrictionDetectedAt > WAKE_LOCK_RESTRICTION_PERSIST_MS) {
            wakeLockRestrictedBySystem = false;
            wakeLockRestrictionReason = null;
            wakeLockFailureCount = 0;
            return false;
        }
        return true;
    }

    public static String getWakeLockRestrictionReason() {
        return wakeLockRestrictionReason;
    }

    private static boolean isDeviceLikelyXiaomi() {
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
}

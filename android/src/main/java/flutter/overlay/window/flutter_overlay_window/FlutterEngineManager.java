package flutter.overlay.window.flutter_overlay_window;

import android.util.Log;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe FlutterEngine manager to prevent segfaults
 */
public class FlutterEngineManager {
    private static final String TAG = "FlutterEngineManager";
    private static final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    
    /**
     * Safely retrieves FlutterEngine from cache with validation
     */
    public static FlutterEngine getEngine(String tag) {
        if (isShuttingDown.get()) {
            Log.w(TAG, "System is shutting down, cannot access engine");
            return null;
        }
        
        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(tag);
            if (engine == null) {
                Log.w(TAG, "Engine not found in cache for tag: " + tag);
                return null;
            }
            
            if (!isEngineValid(engine)) {
                Log.w(TAG, "Engine is in invalid state for tag: " + tag);
                return null;
            }
            
            return engine;
        } catch (Exception e) {
            Log.e(TAG, "Error accessing FlutterEngine: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Validates if FlutterEngine is in a safe state
     */
    public static boolean isEngineValid(FlutterEngine engine) {
        if (engine == null) return false;
        
        try {
            // Check if engine is not destroyed
            if (engine.getDartExecutor() == null) {
                Log.w(TAG, "Engine DartExecutor is null");
                return false;
            }
            
            // Check if lifecycle channel is available
            if (engine.getLifecycleChannel() == null) {
                Log.w(TAG, "Engine LifecycleChannel is null");
                return false;
            }
            
            // Check if renderer is available (but don't block if it's not)
            try {
                if (engine.getRenderer() != null) {
                    Log.d(TAG, "Engine renderer is available");
                } else {
                    Log.d(TAG, "Engine renderer is null, but proceeding");
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not check engine renderer: " + e.getMessage());
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error validating engine: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Safely executes operation on FlutterEngine if valid
     */
    public static boolean executeOnEngine(String tag, EngineOperation operation) {
        FlutterEngine engine = getEngine(tag);
        if (engine == null) {
            Log.w(TAG, "Cannot execute operation: engine is null or invalid");
            return false;
        }
        
        try {
            return operation.execute(engine);
        } catch (Exception e) {
            Log.e(TAG, "Error executing operation on engine: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Marks system as shutting down
     */
    public static void setShuttingDown(boolean shuttingDown) {
        isShuttingDown.set(shuttingDown);
    }
    
    /**
     * Checks if system is shutting down
     */
    public static boolean isShuttingDown() {
        return isShuttingDown.get();
    }
    
    /**
     * Functional interface for engine operations
     */
    @FunctionalInterface
    public interface EngineOperation {
        boolean execute(FlutterEngine engine);
    }
}

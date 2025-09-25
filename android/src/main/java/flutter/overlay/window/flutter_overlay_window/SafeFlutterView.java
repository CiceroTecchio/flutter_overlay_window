package flutter.overlay.window.flutter_overlay_window;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.android.FlutterTextureView;

/**
 * Custom FlutterView that completely blocks accessibility to prevent crashes
 */
public class SafeFlutterView extends FlutterView {
    public SafeFlutterView(Context context) {
        super(context);
        initializeAccessibilityBlocking();
    }
    
    public SafeFlutterView(Context context, FlutterTextureView flutterTextureView) {
        super(context, flutterTextureView);
        initializeAccessibilityBlocking();
    }
    
    private void initializeAccessibilityBlocking() {
        // Completely disable accessibility
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription(null);
        setAccessibilityDelegate(null);
        
        // CRITICAL: Maintain transparency to prevent black background
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        setBackground(null);
    }
    
    @Override
    public void sendAccessibilityEvent(int eventType) {
        // Block ALL accessibility events
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility event: " + eventType);
        return; // Block all events
    }
    
    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        // Block ALL accessibility actions
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility action: " + action);
        return false; // Block all actions
    }
    
    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        // Block initialization
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility event initialization");
        return; // Block initialization
    }
    
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        // Block node info initialization
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility node info initialization");
        return; // Block initialization
    }
}

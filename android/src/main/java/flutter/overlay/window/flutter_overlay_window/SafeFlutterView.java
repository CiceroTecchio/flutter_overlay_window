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
    
    @Override
    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        // CRITICAL: Block requestSendAccessibilityEvent to prevent NullPointerException
        Log.d("SafeFlutterView", "🚫 BLOCKED requestSendAccessibilityEvent to prevent crash");
        return false; // Block the request
    }
    
    @Override
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        // Block ALL accessibility events at the view level
        Log.d("SafeFlutterView", "🚫 BLOCKED sendAccessibilityEventUnchecked");
        return; // Block all events
    }
    
    @Override
    public boolean isShown() {
        // CRITICAL: Always return false to prevent sendAccessibilityEventUncheckedInternal from executing
        // This prevents the crash when parent is null
        Log.d("SafeFlutterView", "🚫 BLOCKED isShown() to prevent accessibility crash");
        return false; // Always return false to prevent accessibility events
    }
    
    @Override
    public boolean isShown(View view) {
        // CRITICAL: Block isShown() for any view to prevent accessibility crashes
        Log.d("SafeFlutterView", "🚫 BLOCKED isShown(View) to prevent accessibility crash");
        return false; // Always return false to prevent accessibility events
    }
    
    @Override
    public void sendAccessibilityEvent(int eventType) {
        // Block ALL accessibility events at the view level
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility event: " + eventType);
        return; // Block all events
    }
    
    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        // Block ALL accessibility actions at the view level
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility action: " + action);
        return false; // Block all actions
    }
    
    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        // Block ALL accessibility event initialization
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility event initialization");
        return; // Block initialization
    }
    
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        // Block ALL accessibility node info initialization
        Log.d("SafeFlutterView", "🚫 BLOCKED accessibility node info initialization");
        return; // Block initialization
    }
    
    @Override
    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        // CRITICAL: Block requestSendAccessibilityEvent to prevent NullPointerException
        Log.d("SafeFlutterView", "🚫 BLOCKED requestSendAccessibilityEvent to prevent crash");
        return false; // Block the request
    }
    
    @Override
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        // Block ALL accessibility events at the view level
        Log.d("SafeFlutterView", "🚫 BLOCKED sendAccessibilityEventUnchecked");
        return; // Block all events
    }
    
    @Override
    public void sendAccessibilityEventUncheckedInternal(AccessibilityEvent event) {
        // CRITICAL: Block the internal method that causes the crash
        Log.d("SafeFlutterView", "🚫 BLOCKED sendAccessibilityEventUncheckedInternal to prevent crash");
        return; // Block the internal method
    }
    
    @Override
    public void dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        // Block accessibility event population
        Log.d("SafeFlutterView", "🚫 BLOCKED dispatchPopulateAccessibilityEvent");
        return; // Block population
    }
    
    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        // Block accessibility event population
        Log.d("SafeFlutterView", "🚫 BLOCKED onPopulateAccessibilityEvent");
        return; // Block population
    }
}

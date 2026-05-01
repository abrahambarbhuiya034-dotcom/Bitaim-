package com.bitaim.carromaim.overlay;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.bitaim.carromaim.AutoShootService;
import com.bitaim.carromaim.capture.MediaProjectionRequestActivity;
import com.bitaim.carromaim.capture.ScreenCaptureService;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import android.graphics.PointF;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * OverlayModule v7 — React Native bridge.
 * Adds AutoPlay support: isAccessibilityReady, isAutoPlayEnabled,
 * requestAccessibilityPermission, setAutoPlay, shootNow, setAutoPlayDelay.
 */
public class OverlayModule extends ReactContextBaseJavaModule {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean autoPlayEnabled  = false;
    private volatile int     autoPlayDelayMs  = 2000;
    private volatile float   shotPower        = 1.0f;
    private volatile float   currentSliderX   = -1f; // unknown initially

    // Cached screen metrics for gesture coordinate computation
    private float screenW = 0, screenH = 0;

    public OverlayModule(ReactApplicationContext ctx) {
        super(ctx);
        WindowManager wm = (WindowManager) ctx.getSystemService("window");
        if (wm != null) {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
    }

    @NonNull @Override
    public String getName() { return "OverlayModule"; }

    // ── Overlay permission ────────────────────────────────────────────────────

    @ReactMethod
    public void canDrawOverlays(Promise p) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            p.resolve(Settings.canDrawOverlays(getReactApplicationContext()));
        } else p.resolve(true);
    }

    @ReactMethod
    public void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getReactApplicationContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getReactApplicationContext().startActivity(i);
    }

    // ── Overlay service ───────────────────────────────────────────────────────

    @ReactMethod
    public void startOverlay(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getReactApplicationContext().startForegroundService(i);
            } else {
                getReactApplicationContext().startService(i);
            }
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_START", e.getMessage()); }
    }

    @ReactMethod
    public void stopOverlay(Promise p) {
        try {
            stopAutoPlayLoop();
            Intent i = new Intent(getReactApplicationContext(), FloatingOverlayService.class);
            i.setAction("ACTION_STOP");
            getReactApplicationContext().startService(i);
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_STOP", e.getMessage()); }
    }

    // ── Screen capture ────────────────────────────────────────────────────────

    @ReactMethod
    public void requestScreenCapture(Promise p) {
        try {
            Intent i = new Intent(getReactApplicationContext(), MediaProjectionRequestActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(i);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_CAPTURE", e.getMessage()); }
    }

    @ReactMethod
    public void stopScreenCapture(Promise p) {
        try {
            stopAutoPlayLoop();
            Intent c = new Intent(getReactApplicationContext(), ScreenCaptureService.class);
            getReactApplicationContext().stopService(c);
            p.resolve(true);
        } catch (Exception e) { p.reject("ERR_STOP_CAPTURE", e.getMessage()); }
    }

    @ReactMethod
    public void isAutoDetectActive(Promise p) {
        p.resolve(ScreenCaptureService.INSTANCE != null);
    }

    // ── Accessibility / AutoPlay ──────────────────────────────────────────────

    /** Returns true when the AccessibilityService is connected. */
    @ReactMethod
    public void isAccessibilityReady(Promise p) {
        p.resolve(AutoShootService.INSTANCE != null);
    }

    /** Returns current AutoPlay enabled state. */
    @ReactMethod
    public void isAutoPlayEnabled(Promise p) {
        p.resolve(autoPlayEnabled);
    }

    /** Open Android Accessibility Settings so user can enable AIMxASSIST. */
    @ReactMethod
    public void requestAccessibilityPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(i);
        } catch (Exception ignored) {}
    }

    /**
     * Enable or disable AutoPlay.
     * Requires AutoShootService to be connected (accessibility enabled).
     */
    @ReactMethod
    public void setAutoPlay(boolean enable, Promise p) {
        if (enable && AutoShootService.INSTANCE == null) {
            p.reject("ERR_NO_ACCESSIBILITY",
                    "Enable AIMxASSIST in Android Accessibility Settings first.");
            return;
        }
        autoPlayEnabled = enable;
        if (enable) startAutoPlayLoop();
        else        stopAutoPlayLoop();
        p.resolve(true);
    }

    /** Fire a single shot immediately using the best detected aim. */
    @ReactMethod
    public void shootNow(Promise p) {
        boolean fired = triggerShot();
        if (fired) p.resolve(true);
        else p.reject("ERR_SHOT", "Could not fire shot — check accessibility permission.");
    }

    /** Set delay between auto shots in milliseconds. */
    @ReactMethod
    public void setAutoPlayDelay(int ms) {
        autoPlayDelayMs = Math.max(300, ms);
    }

    // ── Tunables ──────────────────────────────────────────────────────────────

    @ReactMethod
    public void setShotMode(String m) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setShotMode(m);
    }

    @ReactMethod
    public void setMarginOffset(float dx, float dy) {
        FloatingOverlayService s = FloatingOverlayService.INSTANCE;
        if (s != null) s.setMarginOffset(dx, dy);
    }

    @ReactMethod
    public void setSensitivity(float v) {
        shotPower = Math.max(0.3f, Math.min(v, 3.0f));
    }

    @ReactMethod
    public void setDetectionRadius(float minFrac, float maxFrac) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) { c.setMinRadius(minFrac); c.setMaxRadius(maxFrac); }
    }

    @ReactMethod
    public void setDetectionThreshold(double v) {
        ScreenCaptureService c = ScreenCaptureService.INSTANCE;
        if (c != null) c.setDetectionParam(v);
    }

    // ── AutoPlay loop ─────────────────────────────────────────────────────────

    private final Runnable autoPlayRunnable = new Runnable() {
        @Override public void run() {
            if (!autoPlayEnabled) return;
            triggerShot();
            handler.postDelayed(this, autoPlayDelayMs);
        }
    };

    private void startAutoPlayLoop() {
        handler.removeCallbacks(autoPlayRunnable);
        handler.postDelayed(autoPlayRunnable, autoPlayDelayMs);
    }

    private void stopAutoPlayLoop() {
        autoPlayEnabled = false;
        handler.removeCallbacks(autoPlayRunnable);
    }

    /**
     * Computes the best shot from the current GameState and dispatches it.
     * @return true if gesture was dispatched successfully.
     */
    private boolean triggerShot() {
        AutoShootService svc = AutoShootService.INSTANCE;
        if (svc == null) return false;

        FloatingOverlayService overlay = FloatingOverlayService.INSTANCE;
        if (overlay == null) return false;

        GameState state = overlay.getLatestState();
        if (state == null || state.striker == null || state.coins.isEmpty()) return false;

        // Find best shot: highest-scored (striker close + coin close to pocket)
        PointF bestGhost = null;
        PointF bestTarget = null; // ghost ball position on board
        float  bestScore  = -1f;

        for (Coin coin : state.coins) {
            if (coin.color == Coin.COLOR_STRIKER) continue;
            PointF nearestPocket = null;
            float  nearestDist   = Float.MAX_VALUE;
            for (PointF pk : state.pockets) {
                float d = dist(coin.pos, pk);
                if (d < nearestDist) { nearestDist = d; nearestPocket = pk; }
            }
            if (nearestPocket == null) continue;

            float dx = coin.pos.x - nearestPocket.x;
            float dy = coin.pos.y - nearestPocket.y;
            float len = (float) Math.sqrt(dx*dx + dy*dy);
            if (len < 1f) continue;

            float ghostR = state.striker.radius + coin.radius;
            PointF ghost = new PointF(
                    coin.pos.x + (dx / len) * ghostR,
                    coin.pos.y + (dy / len) * ghostR);

            if (state.board != null &&
                !state.board.contains(ghost.x - 2, ghost.y - 2) &&
                !state.board.contains(ghost.x + 2, ghost.y + 2)) continue;

            float score = 600f / (dist(state.striker.pos, ghost) + 1f)
                        + 300f / (nearestDist + 1f);
            if (coin.color == Coin.COLOR_RED) score *= 1.4f;

            if (score > bestScore) {
                bestScore  = score;
                bestGhost  = ghost;
                bestTarget = nearestPocket;
            }
        }

        if (bestGhost == null) return false;

        // Compute screen coordinates for the gesture
        float strikerScreenX = state.striker.pos.x;
        float strikerScreenY = state.striker.pos.y;

        // Slider bar (maps board X to slider X)
        float sliderY    = screenH * 0.905f;
        float sliderXMin = screenW * 0.213f;
        float sliderXMax = screenW * 0.787f;

        float boardLeft  = state.board != null ? state.board.left  : screenW * 0.056f;
        float boardWidth = state.board != null ? state.board.width(): screenW * 0.888f;
        float playLeft   = boardLeft  + boardWidth * 0.062f;
        float playWidth  = boardWidth * (1f - 2f * 0.062f);

        float tX = (strikerScreenX - playLeft) / Math.max(1f, playWidth);
        float targetSliderX = sliderXMin + tX * (sliderXMax - sliderXMin);
        float curSliderX = currentSliderX < 0 ? (sliderXMin + sliderXMax) / 2f : currentSliderX;

        boolean ok = svc.performShot(
                sliderY, curSliderX, targetSliderX,
                strikerScreenX, strikerScreenY,
                bestGhost.x, bestGhost.y,
                Math.min(1f, shotPower / 3f));

        if (ok) currentSliderX = targetSliderX;
        return ok;
    }

    private float dist(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }
}

package com.bitaim.carromaim.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;

/**
 * FloatingOverlayService — v7.0
 *
 * Key fixes vs v5:
 *  1. AUTOPLAY EXIT BUG FIXED — autoplay now fires ONLY when:
 *       a) Real live CV data is flowing (hasLiveData guard)
 *       b) The board has been stable for STABLE_FRAMES_NEEDED consecutive
 *          frames (pieces not moving)
 *       c) A cooldown of SHOOT_COOLDOWN_MS has passed since the last shot
 *     This means autoplay CANNOT fire while the user is on the AIMxASSIST
 *     app, because the board detector will not see a stable carrom board.
 *
 *  2. Demo state uses setDemoState() instead of setDetectedState() so it
 *     does NOT set hasLiveData and does NOT enable autoplay.
 *
 *  3. Removed the duplicate timer-based autoplay from AimOverlayView —
 *     all autoplay is now driven exclusively from here via handleAutoPlay()
 *     which requires real CV data + stability + cooldown.
 *
 *  4. setAutoPlay() no longer calls aimOverlayView.setAutoplay() (removed
 *     the timer-based API). It just sets the local flag.
 *
 *  5. Popup correctly shows live status of autoplay.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG        = "FloatingOverlayService";
    private static final String CHANNEL_ID = "aimxassist_channel";
    private static final int    NOTIF_ID   = 1001;

    // Board must be stable (pieces not moving) for this many consecutive
    // frames before autoplay fires a shot.
    private static final int   STABLE_FRAMES_NEEDED = 20;
    private static final float STABLE_THRESH_PX     = 12f;

    // Minimum milliseconds between autoplay shots
    private static final long  SHOOT_COOLDOWN_MS    = 3500;

    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager  windowManager;
    private View           floatingBtnView;
    private AimOverlayView aimOverlayView;
    private View           popupView;

    private WindowManager.LayoutParams floatingBtnParams;
    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams popupParams;

    private float   touchStartX, touchStartY;
    private int     viewStartX,  viewStartY;
    private boolean overlayVisible = true;
    private boolean popupShowing   = false;

    private volatile boolean autoPlayEnabled = false;
    private int     stableFrames    = 0;
    private float   lastStrikerX    = Float.NaN;
    private float   lastStrikerY    = Float.NaN;
    private long    lastShootTimeMs = 0L;
    private int     autoPlayDelayMs = 2000;  // mirrors user-set delay

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float dp;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        dp = getResources().getDisplayMetrics().density;
        createNotificationChannel();
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setupAimOverlay();
        setupFloatingButton();
        // Demo state: uses setDemoState (does NOT enable autoplay triggers)
        handler.postDelayed(this::injectDemoState, 400);
    }

    // ── Demo state (visual only — does NOT enable autoplay) ────────────────────

    private void injectDemoState() {
        if (aimOverlayView == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        GameState s = new GameState();
        float side = w * 0.80f;
        float cx   = w / 2f;
        float cy   = h * 0.44f;
        s.board = new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);

        float r = side * 0.024f;
        s.striker = new Coin(cx, cy + side * 0.34f, r * 1.15f, Coin.COLOR_STRIKER, true);

        s.coins.add(new Coin(cx,               cy,              r,     Coin.COLOR_RED,   false));
        s.coins.add(new Coin(cx - side*0.10f,  cy - side*0.06f, r,     Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx + side*0.10f,  cy - side*0.06f, r,     Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx - side*0.19f,  cy + side*0.09f, r,     Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx + side*0.19f,  cy + side*0.09f, r,     Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx,               cy - side*0.21f, r,     Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx - side*0.07f,  cy + side*0.07f, r,     Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx + side*0.07f,  cy + side*0.07f, r,     Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx - side*0.23f,  cy - side*0.11f, r,     Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx + side*0.23f,  cy - side*0.11f, r,     Coin.COLOR_BLACK, false));

        float inset = side * 0.035f;
        s.pockets.add(new PointF(s.board.left  + inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.left  + inset, s.board.bottom - inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.bottom - inset));

        // KEY FIX: use setDemoState, not setDetectedState
        // This keeps hasLiveData = false so autoplay cannot fire on demo data
        aimOverlayView.setDemoState(s);
    }

    // ── Floating button ─────────────────────────────────────────────────────────

    private void setupFloatingButton() {
        floatingBtnView = LayoutInflater.from(this)
                .inflate(R.layout.view_floating_button, null);

        floatingBtnParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatingBtnParams.gravity = Gravity.TOP | Gravity.START;
        floatingBtnParams.x = 16;
        floatingBtnParams.y = 280;

        floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
            boolean wasDrag;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = e.getRawX(); touchStartY = e.getRawY();
                        viewStartX  = floatingBtnParams.x;
                        viewStartY  = floatingBtnParams.y;
                        wasDrag = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchStartX;
                        float dy = e.getRawY() - touchStartY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) wasDrag = true;
                        floatingBtnParams.x = (int)(viewStartX + dx);
                        floatingBtnParams.y = (int)(viewStartY + dy);
                        windowManager.updateViewLayout(floatingBtnView, floatingBtnParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!wasDrag) {
                            if (popupShowing) dismissPopup();
                            else showTogglePopup();
                        }
                        return true;
                }
                return false;
            }
        });
        windowManager.addView(floatingBtnView, floatingBtnParams);
    }

    // ── Toggle popup ──────────────────────────────────────────────────────────

    private void showTogglePopup() {
        if (popupShowing) return;
        popupShowing = true;

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xF2111122);
        int pad = (int)(13 * dp);
        ll.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AIMxASSIST");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, (int)(6 * dp));
        ll.addView(title);

        addPopupRow(ll, pad,
                overlayVisible ? "Lines OFF" : "Lines ON",
                overlayVisible ? 0xFFFF5555 : 0xFF22DD55,
                v -> { toggleAimOverlay(); dismissPopup(); });

        addPopupRow(ll, pad,
                autoPlayEnabled ? "AutoPlay OFF" : "AutoPlay ON",
                autoPlayEnabled ? 0xFFFF5555 : 0xFF6699FF,
                v -> { toggleAutoPlayFromPopup(); dismissPopup(); });

        if (autoPlayEnabled && !AutoShootService.isReady()) {
            TextView hint = new TextView(this);
            hint.setText("Enable Accessibility\nin Settings first!");
            hint.setTextColor(0xFFFF8A00); hint.setTextSize(11);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(0, (int)(5 * dp), 0, 0);
            ll.addView(hint);
        }

        popupView = ll;
        popupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.TOP | Gravity.START;
        popupParams.x = floatingBtnParams.x + (int)(60 * dp);
        popupParams.y = floatingBtnParams.y;
        popupView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) dismissPopup();
            return false;
        });
        windowManager.addView(popupView, popupParams);
        handler.postDelayed(this::dismissPopup, 6000);
    }

    private void addPopupRow(LinearLayout parent, int pad, String text, int color,
                             View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(pad * 2, pad, pad * 2, pad);
        tv.setOnClickListener(click);
        parent.addView(tv);
    }

    private void dismissPopup() {
        if (!popupShowing) return;
        popupShowing = false;
        handler.removeCallbacksAndMessages(null);
        try { if (popupView != null) windowManager.removeView(popupView); } catch (Exception ignored) {}
        popupView = null;
    }

    public void toggleAimOverlay() {
        overlayVisible = !overlayVisible;
        if (aimOverlayView != null) {
            aimOverlayView.setVisibility(overlayVisible ? View.VISIBLE : View.GONE);
            if (overlayVisible) injectDemoState();
        }
        ImageView icon = floatingBtnView != null
                ? floatingBtnView.findViewById(R.id.floating_icon) : null;
        if (icon != null) icon.setAlpha(overlayVisible ? 1.0f : 0.5f);
    }

    private void toggleAutoPlayFromPopup() {
        if (!AutoShootService.isReady()) {
            Log.w(TAG, "AutoPlay toggle from popup: accessibility not ready");
            return;
        }
        setAutoPlay(!autoPlayEnabled);
    }

    // ── Aim overlay ────────────────────────────────────────────────────────────

    private void setupAimOverlay() {
        aimOverlayView = new AimOverlayView(this);

        // Wire swipe listener: translates overlay coordinates → gesture
        aimOverlayView.setAutoplaySwipeListener((fromX, fromY, toX, toY, durationMs) -> {
            AutoShootService svc = AutoShootService.INSTANCE;
            if (svc == null) return;
            float distVal = (float) Math.sqrt(
                    (toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY));
            float power = Math.min(1.0f, distVal / 240f);
            svc.shoot(fromX, fromY, toX, toY, Math.max(0.45f, power));
        });

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        aimOverlayView.setVisibility(View.VISIBLE);
        windowManager.addView(aimOverlayView, overlayParams);
    }

    // ── External API (called from OverlayModule / ScreenCaptureService) ────────

    public void setShotMode(String mode) {
        if (aimOverlayView != null) aimOverlayView.setShotMode(mode);
    }

    public void setMarginOffset(float dx, float dy) { /* removed — calibration feature dropped */ }

    public void setSensitivity(float value) { /* reserved for future use */ }

    public void setAutoPlayDelay(int ms) {
        autoPlayDelayMs = Math.max(500, ms);
        // Note: we do NOT forward to aimOverlayView since it no longer
        // has a timer-based runnable. The delay is used in handleAutoPlay()
        // via the lastShootTimeMs cooldown guard instead.
    }

    /**
     * Called from ScreenCaptureService on every detected frame (real live data).
     * This is the ONLY path that sets hasLiveData and enables autoplay.
     */
    public void onDetectedState(GameState s) {
        if (s == null) return;
        if (aimOverlayView != null) {
            // KEY: use setDetectedState (not setDemoState) — marks hasLiveData = true
            aimOverlayView.setDetectedState(s);
            if (!overlayVisible) {
                overlayVisible = true;
                aimOverlayView.setVisibility(View.VISIBLE);
            }
        }
        // Stability-based autoplay — only fires here with real CV data
        if (autoPlayEnabled && s.striker != null) {
            handleAutoPlay(s);
        }
    }

    /**
     * Single manual shot trigger — called from OverlayModule.shootNow().
     * Requires real live data and accessibility.
     */
    public void shootNow() {
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) {
            Log.w(TAG, "shootNow: AutoShootService not connected");
            return;
        }
        // getLastBestShot() returns null if hasLiveData is false
        AimOverlayView.BestShot best = (aimOverlayView != null)
                ? aimOverlayView.getLastBestShot() : null;
        if (best == null) {
            Log.w(TAG, "shootNow: no best shot available (live data not ready)");
            return;
        }
        Log.i(TAG, "shootNow: dispatching gesture");
        acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, 0.78f);
    }

    /**
     * Enable or disable autoplay. Does NOT start any timer — autoplay fires
     * only via handleAutoPlay() which requires real CV data + stability.
     */
    public void setAutoPlay(boolean enabled) {
        autoPlayEnabled = enabled;
        stableFrames    = 0;
        lastStrikerX    = Float.NaN;
        lastStrikerY    = Float.NaN;
        // Note: NOT calling aimOverlayView.setAutoplay() — timer removed in v7
        Log.i(TAG, "AutoPlay " + (enabled ? "ON" : "OFF"));
    }

    public boolean isAutoPlayEnabled() { return autoPlayEnabled; }

    // ── AutoPlay — stability-based trigger (real CV data ONLY) ─────────────────

    /**
     * Called from onDetectedState() with every live CV frame.
     * Fires a gesture only when:
     *  1. AutoShootService is connected (accessibility granted)
     *  2. A shoot cooldown has passed
     *  3. The striker has been stationary for STABLE_FRAMES_NEEDED frames
     *     (meaning the carrom game is waiting for a shot, not mid-animation)
     *  4. A best shot is available from the AI
     */
    private void handleAutoPlay(GameState s) {
        if (!AutoShootService.isReady()) {
            // Accessibility not granted — silently wait
            return;
        }

        long now = System.currentTimeMillis();
        // Enforce minimum delay between shots (respects user-set delay too)
        long minGap = Math.max(SHOOT_COOLDOWN_MS, autoPlayDelayMs);
        if (now - lastShootTimeMs < minGap) return;

        float sx = s.striker.pos.x;
        float sy = s.striker.pos.y;

        // Count consecutive stable frames (striker not moving)
        if (!Float.isNaN(lastStrikerX)) {
            float moved = (float) Math.sqrt(
                    (sx - lastStrikerX) * (sx - lastStrikerX)
                  + (sy - lastStrikerY) * (sy - lastStrikerY));
            stableFrames = (moved > STABLE_THRESH_PX) ? 0 : stableFrames + 1;
        }
        lastStrikerX = sx;
        lastStrikerY = sy;

        if (stableFrames < STABLE_FRAMES_NEEDED) return;

        // Board is stable — fire the best shot
        // getLastBestShot() returns null if hasLiveData is false (safety guard)
        AimOverlayView.BestShot best = (aimOverlayView != null)
                ? aimOverlayView.getLastBestShot() : null;
        if (best == null) return;

        Log.i(TAG, "AutoPlay: stable for " + stableFrames + " frames — firing gesture");
        AutoShootService.INSTANCE.shoot(
                best.strikerX, best.strikerY,
                best.targetX,  best.targetY, 0.75f);

        lastShootTimeMs = now;
        stableFrames    = 0; // reset so next shot requires fresh stability
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "AIMxASSIST v7 Running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aim assist overlay is active");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent stopIntent = new Intent(this, FloatingOverlayService.class);
        stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);
        Intent openIntent    = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AIMxASSIST v7 Active")
                .setContentText("Aim lines ON — tap floating icon to control")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        INSTANCE = null;
        dismissPopup();
        try { if (floatingBtnView != null) windowManager.removeView(floatingBtnView); } catch (Exception ignored) {}
        try { if (aimOverlayView  != null) windowManager.removeView(aimOverlayView);  } catch (Exception ignored) {}
    }
}

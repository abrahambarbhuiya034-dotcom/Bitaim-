package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import com.bitaim.carromaim.cv.CarromAI;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView — v8.2 VISUAL OVERHAUL
 *
 * Visual style matched to reference video:
 *
 *   ┌────────────────────────────────────────────────────────┐
 *   │  Each shot drawn as TWO connected segments:            │
 *   │   Segment A:  striker centre → coin contact point      │
 *   │               BRIGHT WHITE glow + solid core           │
 *   │   Segment B:  coin centre → pocket centre              │
 *   │               DIM WHITE glow + dark solid core         │
 *   │                                                        │
 *   │  Glow effect: draw each line TWICE —                   │
 *   │    pass 1: wide (14 dp), semi-transparent → halo       │
 *   │    pass 2: narrow (5 dp), fully opaque   → core        │
 *   │                                                        │
 *   │  Best shot (rank 0): brightest / widest                │
 *   │  Lower ranks: progressively thinner / dimmer           │
 *   │                                                        │
 *   │  Ghost-ball contact circle: small, subtle              │
 *   │  Board outline, coins, striker: unchanged              │
 *   └────────────────────────────────────────────────────────┘
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES       = 5;
    private static final float EMA_ALPHA       = 0.10f;
    private static final float CACHE_THRESH_PX = 15f;

    // ── Glow-line visual parameters ───────────────────────────────────────────

    /** Glow halo widths per rank (dp). Widest = best shot. */
    private static final float[] GLOW_WIDTH  = { 16f, 13f, 11f, 9f, 7f };
    /** Core line widths per rank (dp). */
    private static final float[] CORE_WIDTH  = {  5f,  4f,  3.5f, 3f, 2.5f };
    /** Glow alpha per rank (0-255). */
    private static final int[]   GLOW_ALPHA  = { 70, 55, 42, 32, 22 };
    /** Core alpha per rank (0-255). */
    private static final int[]   CORE_ALPHA  = { 255, 220, 185, 150, 110 };

    // Striker→ghost: bright white glow
    private static final int STRIKER_GLOW_COLOR = 0xFFFFFFFF;
    // Striker→ghost core: pure white
    private static final int STRIKER_CORE_COLOR = 0xFFFFFFFF;
    // Coin→pocket glow: white tinted
    private static final int COIN_GLOW_COLOR    = 0xFFCCDDFF;
    // Coin→pocket core: dark/near-black for high contrast on wooden board
    private static final int COIN_CORE_COLOR    = 0xFF1A1A2E;

    // ── Per-rank paints ───────────────────────────────────────────────────────

    private final Paint[] strikerGlowPaints = new Paint[MAX_LINES];
    private final Paint[] strikerCorePaints = new Paint[MAX_LINES];
    private final Paint[] coinGlowPaints    = new Paint[MAX_LINES];
    private final Paint[] coinCorePaints    = new Paint[MAX_LINES];

    // ── Board / coin / misc paints ────────────────────────────────────────────

    private final Paint boardPaint, boardDemoPaint;
    private final Paint strikerRingPaint;
    private final Paint coinOutlinePaint, pocketFill;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint ghostDotPaint;
    private final Paint watermarkPaint;

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode   = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private boolean   hasLiveData = false;
    private final float dp;

    // ── Trajectory cache ─────────────────────────────────────────────────────
    private float cacheStrikerX = Float.NaN;
    private float cacheStrikerY = Float.NaN;
    private List<CarromAI.AiShot> cachedShots = new ArrayList<>();

    // ── BestShot ─────────────────────────────────────────────────────────────
    public static class BestShot {
        public final float strikerX, strikerY, targetX, targetY, powerFrac;
        public BestShot(float sx, float sy, float tx, float ty, float pw) {
            strikerX = sx; strikerY = sy; targetX = tx; targetY = ty; powerFrac = pw;
        }
    }
    private volatile BestShot lastBestShot;
    public BestShot getLastBestShot() { return hasLiveData ? lastBestShot : null; }

    public void setPhysicsBestShot(CarromAI.AiShot aiShot, GameState state) {
        if (aiShot == null || state == null || state.striker == null) return;
        float dx = aiShot.ghostPos.x - state.striker.pos.x;
        float dy = aiShot.ghostPos.y - state.striker.pos.y;
        lastBestShot = new BestShot(
            state.striker.pos.x, state.striker.pos.y,
            state.striker.pos.x + dx * 1.20f,
            state.striker.pos.y + dy * 1.20f,
            aiShot.powerFrac);
    }

    // ── AutoPlay swipe listener ───────────────────────────────────────────────
    public interface AutoplaySwipeListener {
        void onPerformSwipe(float fromX, float fromY,
                            float toX,   float toY,
                            int durationMs, float powerFrac);
    }
    private AutoplaySwipeListener autoplaySwipeListener;
    public void setAutoplaySwipeListener(AutoplaySwipeListener l) {
        autoplaySwipeListener = l;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        // Build per-rank paints
        for (int i = 0; i < MAX_LINES; i++) {
            strikerGlowPaints[i] = makeStroke(STRIKER_GLOW_COLOR,
                    GLOW_WIDTH[i] * dp, GLOW_ALPHA[i]);
            strikerCorePaints[i] = makeStroke(STRIKER_CORE_COLOR,
                    CORE_WIDTH[i] * dp, CORE_ALPHA[i]);
            coinGlowPaints[i]    = makeStroke(COIN_GLOW_COLOR,
                    GLOW_WIDTH[i] * dp, (int)(GLOW_ALPHA[i] * 0.7f));
            coinCorePaints[i]    = makeStroke(COIN_CORE_COLOR,
                    CORE_WIDTH[i] * dp, CORE_ALPHA[i]);
        }

        // Board outline
        boardPaint = makeStroke(0x88FFD700, 1.5f * dp, 255);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        boardDemoPaint = makeStroke(0x44FFD700, 1.0f * dp, 255);
        boardDemoPaint.setPathEffect(new DashPathEffect(new float[]{4*dp, 8*dp}, 0));

        // Striker ring — gold outline, no fill
        strikerRingPaint = makeStroke(0xFFFFD700, 2.5f * dp, 255);

        // Coin paints
        coinOutlinePaint = makeStroke(0x99FFFFFF, 1.2f * dp, 255);
        pocketFill       = makeFill(0xAA2ECC71);
        blackFill        = makeFill(0xBB101010);
        whiteFill        = makeFill(0xBBEEEEEE);
        redFill          = makeFill(0xBBFF3344);

        // Ghost-ball contact dot
        ghostDotPaint = makeStroke(0xCCFFFFFF, 2.0f * dp, 255);

        // Watermark
        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x44FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);
        watermarkPaint.setShadowLayer(dp, 0, 0, Color.BLACK);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode) {
        this.shotMode = mode;
        invalidateCache();
        postInvalidate();
    }

    public void setDetectedState(GameState s) { setStateInternal(s, true); }
    public void setDemoState(GameState s)     { setStateInternal(s, false); }

    private void setStateInternal(GameState s, boolean live) {
        if (s == null) return;
        if (live) hasLiveData = true;
        detected = s;
        applySmoothing(s);
        if (live) rebuildCacheIfNeeded();
        postInvalidate();
    }

    // ── Cache management ──────────────────────────────────────────────────────

    private void invalidateCache() {
        cacheStrikerX = Float.NaN;
        cacheStrikerY = Float.NaN;
    }

    private void rebuildCacheIfNeeded() {
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) { invalidateCache(); return; }

        float sx = s.striker.pos.x, sy = s.striker.pos.y;
        if (!Float.isNaN(cacheStrikerX)) {
            float dx = sx - cacheStrikerX, dy = sy - cacheStrikerY;
            if (dx*dx + dy*dy < CACHE_THRESH_PX * CACHE_THRESH_PX) return;
        }
        cacheStrikerX = sx;
        cacheStrikerY = sy;

        cachedShots = computeFilteredShots(s);

        // Update best-shot for autoplay
        if (!cachedShots.isEmpty()) {
            CarromAI.AiShot best = cachedShots.get(0);
            float dx = best.ghostPos.x - sx, dy = best.ghostPos.y - sy;
            lastBestShot = new BestShot(sx, sy,
                sx + dx * 1.20f, sy + dy * 1.20f, best.powerFrac);
        } else {
            lastBestShot = null;
        }
    }

    public void performBestSwipe() {
        if (!hasLiveData) return;
        BestShot bs = lastBestShot;
        if (bs == null || autoplaySwipeListener == null) return;
        autoplaySwipeListener.onPerformSwipe(
            bs.strikerX, bs.strikerY, bs.targetX, bs.targetY, 70, bs.powerFrac);
    }

    // ── Shot computation ──────────────────────────────────────────────────────

    private List<CarromAI.AiShot> computeFilteredShots(GameState s) {
        List<CarromAI.AiShot> all = CarromAI.findBestShots(s, MAX_LINES * 3);
        List<CarromAI.AiShot> out = new ArrayList<>();
        for (CarromAI.AiShot shot : all) {
            if (modeAllows(shot.wallsNeeded, shot.isBank)) {
                out.add(shot);
                if (out.size() >= MAX_LINES) break;
            }
        }
        return out;
    }

    private boolean modeAllows(int walls, boolean isBank) {
        switch (shotMode) {
            case MODE_DIRECT: return walls == 0 && !isBank;
            case MODE_AI:     return walls == 0;
            case MODE_GOLDEN: return walls <= 1;
            case MODE_LUCKY:  return walls <= 2;
            default:          return true;
        }
    }

    // ── EMA smoothing ─────────────────────────────────────────────────────────

    private void applySmoothing(GameState raw) {
        if (smoothed == null) { smoothed = raw; return; }
        GameState out = new GameState();
        out.board = smoothRect(smoothed.board, raw.board);
        if (raw.striker != null) {
            out.striker = (smoothed.striker != null)
                ? new Coin(ema(smoothed.striker.pos.x, raw.striker.pos.x),
                           ema(smoothed.striker.pos.y, raw.striker.pos.y),
                           ema(smoothed.striker.radius, raw.striker.radius),
                           Coin.COLOR_STRIKER, true)
                : raw.striker;
        }
        out.coins   = smoothCoins(smoothed.coins, raw.coins);
        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    private List<Coin> smoothCoins(List<Coin> prev, List<Coin> next) {
        if (prev == null || prev.isEmpty()) return next;
        if (next == null || next.isEmpty()) return new ArrayList<>();
        List<Coin>  result  = new ArrayList<>(next.size());
        boolean[]   matched = new boolean[prev.size()];
        for (Coin n : next) {
            Coin bestPrev = null; float bestD = Float.MAX_VALUE; int bi = -1;
            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue;
                float dx = p.pos.x-n.pos.x, dy = p.pos.y-n.pos.y;
                float d  = (float)Math.sqrt(dx*dx+dy*dy);
                if (d < bestD && d < (p.radius+n.radius)*2f) { bestD=d; bestPrev=p; bi=i; }
            }
            if (bestPrev != null) {
                matched[bi] = true;
                result.add(new Coin(ema(bestPrev.pos.x, n.pos.x),
                                    ema(bestPrev.pos.y, n.pos.y),
                                    ema(bestPrev.radius, n.radius),
                                    n.color, n.isStriker));
            } else { result.add(n); }
        }
        return result;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }
    private float ema(float p, float n) { return p + EMA_ALPHA*(n-p); }

    // ── DRAW ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        RectF board = s.board;

        // Board outline
        if (board != null) {
            canvas.drawRect(board, hasLiveData ? boardPaint : boardDemoPaint);
            canvas.drawText("created by abraham / Xhay",
                    board.centerX(), board.centerY(), watermarkPaint);
        }

        // Pocket indicators
        float pocketR = (board != null) ? board.width() * 0.032f : 13 * dp;
        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, pocketR, pocketFill);

        List<CarromAI.AiShot> shots = cachedShots;

        // ── Draw all shot lines back-to-front (rank MAX-1 first, rank 0 last) ──
        for (int rank = shots.size() - 1; rank >= 0; rank--) {
            CarromAI.AiShot shot = shots.get(rank);

            float sX = s.striker.pos.x, sY = s.striker.pos.y;
            float gX = shot.ghostPos.x,  gY = shot.ghostPos.y;

            // ── Segment A: striker centre → ghost-ball contact point ──────────
            // Pass 1: wide glow halo
            drawLineSafe(canvas, sX, sY, gX, gY, strikerGlowPaints[rank], board);
            // Pass 2: narrow bright core
            drawLineSafe(canvas, sX, sY, gX, gY, strikerCorePaints[rank], board);

            // ── Segment B: coin centre → pocket ───────────────────────────────
            if (shot.coin != null && shot.pocket != null) {
                float cX = shot.coin.pos.x, cY = shot.coin.pos.y;
                float pX = shot.pocket.x,   pY = shot.pocket.y;

                // Pass 1: wide glow halo
                drawLineSafe(canvas, cX, cY, pX, pY, coinGlowPaints[rank], board);
                // Pass 2: narrow dark core
                drawLineSafe(canvas, cX, cY, pX, pY, coinCorePaints[rank], board);
            }

            // ── Ghost-ball dot at contact point (subtle circle) ───────────────
            float ghostR = s.striker.radius * 0.55f;
            if (rank == 0) {
                // Best shot: slightly larger, brighter ring
                canvas.drawCircle(gX, gY, ghostR * 1.2f, ghostDotPaint);
            } else {
                ghostDotPaint.setAlpha((int)(255 * (1f - rank * 0.18f)));
                canvas.drawCircle(gX, gY, ghostR, ghostDotPaint);
                ghostDotPaint.setAlpha(255);
            }
        }

        // ── Coins ─────────────────────────────────────────────────────────────
        for (Coin c : s.coins) {
            Paint fill = (c.color == Coin.COLOR_BLACK) ? blackFill
                       : (c.color == Coin.COLOR_RED)   ? redFill
                                                       : whiteFill;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, fill);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }

        // ── Striker (always on top) ───────────────────────────────────────────
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, whiteFill);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerRingPaint);
    }

    // ── Draw helpers ──────────────────────────────────────────────────────────

    /** Draw a single line, Cohen-Sutherland clipped to the board rect. */
    private void drawLineSafe(Canvas canvas,
                               float x0, float y0, float x1, float y1,
                               Paint p, RectF board) {
        if (!isFinite(x0, y0, x1, y1)) return;
        if (board != null) {
            float[] c = clipLineToRect(x0, y0, x1, y1, board);
            if (c != null) canvas.drawLine(c[0], c[1], c[2], c[3], p);
        } else {
            canvas.drawLine(x0, y0, x1, y1, p);
        }
    }

    /**
     * Cohen-Sutherland line clipping against a rectangle.
     * Returns {x0, y0, x1, y1} of the visible segment, or null if fully outside.
     */
    private static float[] clipLineToRect(float x0, float y0, float x1, float y1, RectF r) {
        int code0 = outcode(x0, y0, r);
        int code1 = outcode(x1, y1, r);
        while (true) {
            if ((code0 | code1) == 0) return new float[]{x0, y0, x1, y1};
            if ((code0 & code1) != 0) return null;
            int out = (code0 != 0) ? code0 : code1;
            float x, y;
            if      ((out & 8) != 0) { x = x0 + (x1-x0)*(r.top-y0)/(y1-y0);    y = r.top;    }
            else if ((out & 4) != 0) { x = x0 + (x1-x0)*(r.bottom-y0)/(y1-y0); y = r.bottom; }
            else if ((out & 2) != 0) { y = y0 + (y1-y0)*(r.right-x0)/(x1-x0);  x = r.right;  }
            else                     { y = y0 + (y1-y0)*(r.left-x0)/(x1-x0);   x = r.left;   }
            if (out == code0) { x0 = x; y0 = y; code0 = outcode(x0, y0, r); }
            else              { x1 = x; y1 = y; code1 = outcode(x1, y1, r); }
        }
    }

    private static int outcode(float x, float y, RectF r) {
        int c = 0;
        if (x < r.left)   c |= 1;
        if (x > r.right)  c |= 2;
        if (y > r.bottom) c |= 4;
        if (y < r.top)    c |= 8;
        return c;
    }

    private static boolean isFinite(float... v) {
        for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f)) return false;
        return true;
    }

    // ── Paint factory ─────────────────────────────────────────────────────────

    private static Paint makeStroke(int color, float widthPx, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setAlpha(alpha);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(widthPx);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static Paint makeFill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }
}

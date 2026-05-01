package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.bitaim.carromaim.cv.CarromAI;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView — v7.0
 *
 * Changes vs v4:
 *  1. AUTOPLAY BUG FIXED — timer-based runnable removed entirely.
 *     AutoPlay now fires ONLY via stability-based trigger in
 *     FloatingOverlayService.handleAutoPlay() when real CV data shows
 *     a stable board. This prevents gestures from landing on the
 *     AIMxASSIST app UI and "exiting" the app.
 *
 *  2. LINES FIXED — computeBestShots() completely replaced with
 *     CarromAI.findBestShots() which uses proper ghost-ball geometry,
 *     path-clearance checks, foul-safety filtering, and multi-pocket
 *     evaluation. All 5 prediction lines now use correct targeting.
 *
 *  3. hasLiveData flag — overlay only draws live predictions and
 *     allows autoplay when real CV data (from ScreenCaptureService)
 *     is present. Demo state still shows for visual reference but
 *     is clearly distinguished.
 *
 *  4. Multi-line draw: top 5 AI shots drawn with rank colours/widths.
 *     Each shot gets: striker→ghost line, ghost circle, coin→pocket
 *     direction, and post-contact trajectory simulation.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES  = 5;
    private static final float EMA_ALPHA  = 0.20f;

    private static final int[]   LINE_COLORS = {
        0xFFFFD700, 0xFF00E5FF, 0xFFFF8A00, 0xFFD946EF, 0xFF22C55E
    };
    private static final int[]   LINE_ALPHAS  = { 255, 178, 127, 89, 51 };
    private static final float[] LINE_WIDTHS  = { 3.5f, 3.0f, 2.5f, 2.0f, 1.5f };

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private boolean   hasLiveData = false;  // true only when real CV data received
    private final float dp;

    // ── BestShot — used by FloatingOverlayService for AutoPlay gestures ──────

    public static class BestShot {
        public final float strikerX;
        public final float strikerY;
        public final float targetX;
        public final float targetY;
        public BestShot(float sx, float sy, float tx, float ty) {
            strikerX = sx; strikerY = sy; targetX = tx; targetY = ty;
        }
    }

    private volatile BestShot lastBestShot;

    public BestShot getLastBestShot() {
        // Only return a shot if we have real live data (not demo)
        return hasLiveData ? lastBestShot : null;
    }

    // ── AutoPlay listener — called by FloatingOverlayService ─────────────────
    // NOTE: No timer runnable here. AutoPlay is triggered by the stability-based
    // mechanism in FloatingOverlayService.handleAutoPlay() only.

    public interface AutoplaySwipeListener {
        void onPerformSwipe(float fromX, float fromY,
                            float toX,   float toY,
                            int   durationMs);
    }

    private AutoplaySwipeListener autoplaySwipeListener;

    // ── Per-rank paint sets ───────────────────────────────────────────────────
    private final Paint[] aimPaints      = new Paint[MAX_LINES];
    private final Paint[] bouncePaints   = new Paint[MAX_LINES];
    private final Paint[] coinPathPaints = new Paint[MAX_LINES];

    private final Paint strikerPaint, coinOutlinePaint, pocketFill;
    private final Paint boardPaint, boardDemoPaint;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint watermarkPaint;
    private final Paint strikerLinePaint, cyanLinePaint, arrowPaint;
    private final Paint ghostPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        for (int i = 0; i < MAX_LINES; i++) {
            int a = LINE_ALPHAS[i]; float w = LINE_WIDTHS[i];
            aimPaints[i]      = strokeA(LINE_COLORS[i], w,        a);
            bouncePaints[i]   = strokeA(0xFF00E5FF,      w - 0.5f, a);
            coinPathPaints[i] = strokeA(LINE_COLORS[i],  w,        (int)(a * 0.6f));
        }

        strikerPaint     = stroke(0xFFFFD700, 2.2f);
        coinOutlinePaint = stroke(0x88FFFFFF, 1.5f);
        pocketFill       = fill(0x882ECC71);

        boardPaint = stroke(0x66FFD700, 1.2f);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        boardDemoPaint = stroke(0x33FFD700, 1.0f);
        boardDemoPaint.setPathEffect(new DashPathEffect(new float[]{4*dp, 8*dp}, 0));

        blackFill = fill(0x55000000);
        whiteFill = fill(0x44FFFFFF);
        redFill   = fill(0x55FF3D71);

        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x33FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);
        watermarkPaint.setShadowLayer(1 * dp, 0, 0, Color.BLACK);

        strikerLinePaint = thickLine(0xCCFFFFFF, 3.0f);
        cyanLinePaint    = thickLine(0xCC00E5FF, 2.5f);

        ghostPaint = stroke(0x99FFFFFF, 1.5f);
        ghostPaint.setStyle(Paint.Style.STROKE);

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFF8C00);
        arrowPaint.setStyle(Paint.Style.FILL);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Paint helpers ─────────────────────────────────────────────────────────

    private Paint thickLine(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setShadowLayer(2 * dp, 0, 0, 0x88000000);
        return p;
    }

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private Paint strokeA(int color, float w, int alpha) {
        Paint p = stroke(color, w);
        p.setAlpha(alpha);
        return p;
    }

    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL);
        return p;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode) { this.shotMode = mode; postInvalidate(); }

    public void setAutoplaySwipeListener(AutoplaySwipeListener l) {
        autoplaySwipeListener = l;
    }

    /**
     * Called by FloatingOverlayService with real CV data from ScreenCaptureService.
     * Sets hasLiveData = true, enabling autoplay.
     */
    public void setDetectedState(GameState s) {
        setDetectedStateInternal(s, true);
    }

    /**
     * Called for demo data only (startup visual). Does NOT enable autoplay.
     */
    public void setDemoState(GameState s) {
        setDetectedStateInternal(s, false);
    }

    private void setDetectedStateInternal(GameState s, boolean isLive) {
        if (s == null) return;
        if (isLive) hasLiveData = true;
        detected = s;
        applySmoothing(s);
        cacheBestShot();
        postInvalidate();
    }

    /**
     * Compute and cache the best shot. Used by FloatingOverlayService
     * for stability-based autoplay trigger.
     */
    private void cacheBestShot() {
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) { lastBestShot = null; return; }

        List<CarromAI.AiShot> shots = computeFilteredShots(s);
        if (shots.isEmpty()) { lastBestShot = null; return; }

        CarromAI.AiShot best = shots.get(0);
        float dx = best.ghostPos.x - s.striker.pos.x;
        float dy = best.ghostPos.y - s.striker.pos.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) { lastBestShot = null; return; }

        // Overshoot slightly so the striker physically reaches the coin
        float factor = 1.18f;
        lastBestShot = new BestShot(
            s.striker.pos.x, s.striker.pos.y,
            s.striker.pos.x + dx * factor,
            s.striker.pos.y + dy * factor);
    }

    /**
     * Perform a single swipe for the best shot. Called by FloatingOverlayService
     * for the stability-based autoplay trigger (NOT a timer — fires when the
     * board has been stable for N frames with real CV data).
     */
    public void performBestSwipe() {
        if (!hasLiveData) return; // never fire on demo data
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null || autoplaySwipeListener == null) return;

        List<CarromAI.AiShot> shots = computeFilteredShots(s);
        if (shots.isEmpty()) return;

        CarromAI.AiShot best = shots.get(0);
        float dx   = best.ghostPos.x - s.striker.pos.x;
        float dy   = best.ghostPos.y - s.striker.pos.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float factor  = 1.18f;
        float toX     = s.striker.pos.x + dx * factor;
        float toY     = s.striker.pos.y + dy * factor;
        int   duration = 75;

        lastBestShot = new BestShot(s.striker.pos.x, s.striker.pos.y, toX, toY);
        autoplaySwipeListener.onPerformSwipe(
            s.striker.pos.x, s.striker.pos.y, toX, toY, duration);
    }

    // ── EMA smoothing ─────────────────────────────────────────────────────────

    private void applySmoothing(GameState raw) {
        if (smoothed == null) { smoothed = raw; return; }
        GameState out = new GameState();
        out.board = smoothRect(smoothed.board, raw.board);

        if (raw.striker != null) {
            if (smoothed.striker != null) {
                out.striker = new Coin(
                    ema(smoothed.striker.pos.x, raw.striker.pos.x),
                    ema(smoothed.striker.pos.y, raw.striker.pos.y),
                    ema(smoothed.striker.radius, raw.striker.radius),
                    Coin.COLOR_STRIKER, true);
            } else {
                out.striker = raw.striker;
            }
        }

        out.coins   = smoothCoins(smoothed.coins, raw.coins);
        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    private List<Coin> smoothCoins(List<Coin> prev, List<Coin> next) {
        if (prev == null || prev.isEmpty()) return next;
        if (next == null || next.isEmpty()) return new ArrayList<>();

        List<Coin> result  = new ArrayList<>(next.size());
        boolean[]  matched = new boolean[prev.size()];

        for (Coin n : next) {
            Coin  bestPrev = null;
            float bestDist = Float.MAX_VALUE;
            int   bestIdx  = -1;

            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue;
                float dx = p.pos.x - n.pos.x, dy = p.pos.y - n.pos.y;
                float d  = (float) Math.sqrt(dx*dx + dy*dy);
                float threshold = (p.radius + n.radius) * 2.0f;
                if (d < bestDist && d < threshold) {
                    bestDist = d; bestPrev = p; bestIdx = i;
                }
            }

            if (bestPrev != null) {
                matched[bestIdx] = true;
                result.add(new Coin(
                    ema(bestPrev.pos.x, n.pos.x),
                    ema(bestPrev.pos.y, n.pos.y),
                    ema(bestPrev.radius, n.radius),
                    n.color, n.isStriker));
            } else {
                result.add(n);
            }
        }
        return result;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }

    private float ema(float p, float n) { return p + EMA_ALPHA * (n - p); }

    // ── Shot computation ──────────────────────────────────────────────────────

    /**
     * Get shots filtered by current shotMode.
     */
    private List<CarromAI.AiShot> computeFilteredShots(GameState s) {
        // Ask CarromAI for top candidates (more than MAX_LINES so we can filter)
        List<CarromAI.AiShot> all = CarromAI.findBestShots(s, MAX_LINES * 4);

        List<CarromAI.AiShot> filtered = new ArrayList<>();
        for (CarromAI.AiShot shot : all) {
            if (shotModeAllows(shot.wallsNeeded, shot.isBank)) {
                filtered.add(shot);
                if (filtered.size() >= MAX_LINES) break;
            }
        }
        return filtered;
    }

    private boolean shotModeAllows(int wallsNeeded, boolean isBank) {
        switch (shotMode) {
            case MODE_DIRECT: return wallsNeeded == 0 && !isBank;
            case MODE_AI:     return wallsNeeded == 0;
            case MODE_GOLDEN: return wallsNeeded <= 1;
            case MODE_LUCKY:  return wallsNeeded <= 2;
            case MODE_ALL:
            default:          return true;
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        // Board outline (dimmer when showing demo data)
        if (s.board != null) {
            canvas.drawRect(s.board, hasLiveData ? boardPaint : boardDemoPaint);
            canvas.drawText("created by abraham / Xhay",
                    s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        // Pockets
        for (PointF p : s.pockets) {
            canvas.drawCircle(p.x, p.y, 13 * dp, pocketFill);
        }

        // Get ranked shots
        List<CarromAI.AiShot> shots = computeFilteredShots(s);

        // ── Draw prediction lines (all ranks, dimming from rank 1 to 5) ──────
        for (int rank = shots.size() - 1; rank >= 0; rank--) {
            CarromAI.AiShot shot = shots.get(rank);
            Paint aimP    = aimPaints[rank];
            Paint bounceP = bouncePaints[rank];
            Paint coinP   = coinPathPaints[rank];

            // Striker → ghost-ball line
            canvas.drawLine(
                s.striker.pos.x, s.striker.pos.y,
                shot.ghostPos.x, shot.ghostPos.y, aimP);

            // Ghost-ball circle at contact point
            canvas.drawCircle(
                shot.ghostPos.x, shot.ghostPos.y,
                s.striker.radius, rank == 0 ? coinOutlinePaint : ghostPaint);

            // Coin → pocket direction
            if (shot.coin != null && shot.pocket != null) {
                canvas.drawLine(
                    shot.coin.pos.x, shot.coin.pos.y,
                    shot.pocket.x, shot.pocket.y, coinP);
            }

            // Post-contact trajectory (striker path after hitting coin)
            if (rank < 3) { // only top 3 to keep overlay readable
                List<TrajectorySimulator.PathSegment> segs = simulator.simulate(
                    s.striker, shot.ghostPos, s.coins, s.pockets, s.board, 1.0f);
                int segDrawn = 0;
                for (TrajectorySimulator.PathSegment seg : segs) {
                    if (segDrawn >= 2) break;
                    drawPolyline(canvas, seg.points,
                        seg.wallBounces == 0 ? aimP : bounceP);
                    segDrawn++;
                }
            }
        }

        // ── Arrow on best shot (rank 0) ───────────────────────────────────────
        if (!shots.isEmpty()) {
            drawArrow(canvas, s.striker.pos, shots.get(0).ghostPos);
        }

        // ── Draw coins on top of lines ────────────────────────────────────────
        for (Coin c : s.coins) {
            Paint f = c.color == Coin.COLOR_BLACK ? blackFill
                    : c.color == Coin.COLOR_RED   ? redFill : whiteFill;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, f);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }

        // Striker
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, whiteFill);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerPaint);
    }

    private void drawArrow(Canvas canvas, PointF from, PointF to) {
        float dx = to.x - from.x, dy = to.y - from.y;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1f) return;
        float ux = dx / len, uy = dy / len;

        float tipX = from.x + ux * 22 * dp;
        float tipY = from.y + uy * 22 * dp;

        float arrowLen = 14 * dp;
        float arrowW   = 7  * dp;

        Path path = new Path();
        path.moveTo(tipX, tipY);
        path.lineTo(tipX - ux*arrowLen + uy*arrowW, tipY - uy*arrowLen - ux*arrowW);
        path.lineTo(tipX - ux*arrowLen - uy*arrowW, tipY - uy*arrowLen + ux*arrowW);
        path.close();
        canvas.drawPath(path, arrowPaint);
    }

    private void drawPolyline(Canvas c, List<PointF> pts, Paint p) {
        for (int i = 1; i < pts.size(); i++) {
            float x0 = pts.get(i-1).x, y0 = pts.get(i-1).y;
            float x1 = pts.get(i).x,   y1 = pts.get(i).y;
            if (!isFinite(x0, y0, x1, y1)) continue;
            c.drawLine(x0, y0, x1, y1, p);
        }
    }

    private static boolean isFinite(float... vals) {
        for (float v : vals) if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        return true;
    }
}

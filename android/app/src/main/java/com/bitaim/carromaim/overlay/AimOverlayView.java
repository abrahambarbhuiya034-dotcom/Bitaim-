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
 * AimOverlayView — v8.0 GODMODE
 *
 * § Lines    Uses CarromAI.findBestShots() — full ghost-ball geometry,
 *            path-clearance, bank shots, foul-safety, 5 ranked colours.
 *
 * § AutoPlay Uses CarromAI.findBestShotPhysics() result cached by
 *            FloatingOverlayService on a background thread.
 *            Timer-based runnable REMOVED — fires only on stable board
 *            with live CV data (prevents exit-app crash).
 *
 * § hasLiveData flag — demo state NEVER enables autoplay or gestures.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES = 5;
    private static final float EMA_ALPHA = 0.18f;

    private static final int[]   LINE_COLORS = {
        0xFFFFD700, 0xFF00E5FF, 0xFFFF8A00, 0xFFD946EF, 0xFF22C55E
    };
    private static final int[]   LINE_ALPHAS = { 255, 178, 127, 89, 51 };
    private static final float[] LINE_WIDTHS = { 3.5f, 3.0f, 2.5f, 2.0f, 1.5f };

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode   = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private boolean   hasLiveData = false;
    private final float dp;

    // ── BestShot (used by FloatingOverlayService for autoplay gesture) ────────
    public static class BestShot {
        public final float strikerX, strikerY;
        public final float targetX,  targetY;   // ghost contact point (screen px)
        public final float powerFrac;            // 0.35–1.0 calibrated power
        public BestShot(float sx, float sy, float tx, float ty, float pw) {
            strikerX = sx; strikerY = sy; targetX = tx; targetY = ty; powerFrac = pw;
        }
    }

    private volatile BestShot lastBestShot;

    /** Returns best shot ONLY when live CV data present (not demo). */
    public BestShot getLastBestShot() { return hasLiveData ? lastBestShot : null; }

    /** Override best shot with physics-validated result from background thread. */
    public void setPhysicsBestShot(CarromAI.AiShot aiShot, GameState state) {
        if (aiShot == null || state == null || state.striker == null) return;
        // Overshoot factor so the striker physically reaches the coin
        float dx = aiShot.ghostPos.x - state.striker.pos.x;
        float dy = aiShot.ghostPos.y - state.striker.pos.y;
        float factor = 1.20f;
        lastBestShot = new BestShot(
            state.striker.pos.x, state.striker.pos.y,
            state.striker.pos.x + dx * factor,
            state.striker.pos.y + dy * factor,
            aiShot.powerFrac);
    }

    // ── AutoPlay swipe listener ───────────────────────────────────────────────
    public interface AutoplaySwipeListener {
        void onPerformSwipe(float fromX, float fromY,
                            float toX,   float toY,
                            int   durationMs, float powerFrac);
    }
    private AutoplaySwipeListener autoplaySwipeListener;
    public void setAutoplaySwipeListener(AutoplaySwipeListener l) {
        autoplaySwipeListener = l;
    }

    // ── Per-rank paints ───────────────────────────────────────────────────────
    private final Paint[] aimPaints      = new Paint[MAX_LINES];
    private final Paint[] bouncePaints   = new Paint[MAX_LINES];
    private final Paint[] coinPathPaints = new Paint[MAX_LINES];

    private final Paint strikerPaint, coinOutlinePaint, pocketFill;
    private final Paint boardPaint, boardDemoPaint;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint watermarkPaint;
    private final Paint ghostPaint, arrowPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        for (int i = 0; i < MAX_LINES; i++) {
            int a = LINE_ALPHAS[i]; float w = LINE_WIDTHS[i];
            aimPaints[i]      = strokeA(LINE_COLORS[i], w,        a);
            bouncePaints[i]   = strokeA(0xFF00E5FF,      w - 0.5f, a);
            coinPathPaints[i] = strokeA(LINE_COLORS[i],  w,        (int)(a * 0.55f));
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
        watermarkPaint.setShadowLayer(dp, 0, 0, Color.BLACK);

        ghostPaint = stroke(0x99FFFFFF, 1.5f);
        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFF8C00);
        arrowPaint.setStyle(Paint.Style.FILL);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode) { this.shotMode = mode; postInvalidate(); }

    /** Live CV data — enables autoplay. */
    public void setDetectedState(GameState s) { setStateInternal(s, true); }

    /** Demo data — does NOT enable autoplay. */
    public void setDemoState(GameState s) { setStateInternal(s, false); }

    private void setStateInternal(GameState s, boolean live) {
        if (s == null) return;
        if (live) hasLiveData = true;
        detected = s;
        applySmoothing(s);
        if (live) cacheGeometryShot(); // fast geometry cache
        postInvalidate();
    }

    /** Fast geometry-based shot cache (updates every live CV frame). */
    private void cacheGeometryShot() {
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) { lastBestShot = null; return; }
        List<CarromAI.AiShot> shots = computeFilteredShots(s);
        if (shots.isEmpty()) { lastBestShot = null; return; }
        CarromAI.AiShot best = shots.get(0);
        float dx = best.ghostPos.x - s.striker.pos.x;
        float dy = best.ghostPos.y - s.striker.pos.y;
        float factor = 1.20f;
        lastBestShot = new BestShot(
            s.striker.pos.x, s.striker.pos.y,
            s.striker.pos.x + dx * factor,
            s.striker.pos.y + dy * factor,
            best.powerFrac);
    }

    /**
     * Perform swipe using the cached best shot. Called from FloatingOverlayService
     * after stability + live-data check (no timer — crash-proof).
     */
    public void performBestSwipe() {
        if (!hasLiveData) return;
        BestShot bs = lastBestShot;
        if (bs == null || autoplaySwipeListener == null) return;
        autoplaySwipeListener.onPerformSwipe(
            bs.strikerX, bs.strikerY, bs.targetX, bs.targetY,
            70, bs.powerFrac);
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
        List<Coin> result  = new ArrayList<>(next.size());
        boolean[]  matched = new boolean[prev.size()];
        for (Coin n : next) {
            Coin bestPrev = null; float bestD = Float.MAX_VALUE; int bi = -1;
            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue;
                float dx = p.pos.x-n.pos.x, dy = p.pos.y-n.pos.y;
                float d  = (float) Math.sqrt(dx*dx+dy*dy);
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

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        if (s.board != null) {
            canvas.drawRect(s.board, hasLiveData ? boardPaint : boardDemoPaint);
            canvas.drawText("created by abraham / Xhay",
                    s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, 13*dp, pocketFill);

        List<CarromAI.AiShot> shots = computeFilteredShots(s);

        // Draw all ranked shots (back to front so rank-1 is on top)
        for (int rank = shots.size()-1; rank >= 0; rank--) {
            CarromAI.AiShot shot = shots.get(rank);
            Paint aimP    = aimPaints[rank];
            Paint bounceP = bouncePaints[rank];
            Paint coinP   = coinPathPaints[rank];

            // Striker → ghost line
            canvas.drawLine(s.striker.pos.x, s.striker.pos.y,
                            shot.ghostPos.x,  shot.ghostPos.y, aimP);
            // Ghost-ball circle
            canvas.drawCircle(shot.ghostPos.x, shot.ghostPos.y,
                              s.striker.radius, rank == 0 ? coinOutlinePaint : ghostPaint);
            // Coin → pocket
            if (shot.coin != null && shot.pocket != null)
                canvas.drawLine(shot.coin.pos.x, shot.coin.pos.y,
                                shot.pocket.x,   shot.pocket.y, coinP);
            // Trajectory (top 3 only)
            if (rank < 3) {
                List<TrajectorySimulator.PathSegment> segs = simulator.simulate(
                    s.striker, shot.ghostPos, s.coins, s.pockets, s.board, 1.0f);
                int drawn = 0;
                for (TrajectorySimulator.PathSegment seg : segs) {
                    if (drawn >= 2) break;
                    drawPolyline(canvas, seg.points,
                        seg.wallBounces == 0 ? aimP : bounceP);
                    drawn++;
                }
            }
        }

        // Orange aim arrow on best shot
        if (!shots.isEmpty())
            drawArrow(canvas, s.striker.pos, shots.get(0).ghostPos);

        // Coins
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

    // ── Draw helpers ──────────────────────────────────────────────────────────

    private void drawArrow(Canvas canvas, PointF from, PointF to) {
        float dx = to.x-from.x, dy = to.y-from.y;
        float len = (float) Math.sqrt(dx*dx+dy*dy);
        if (len < 1f) return;
        float ux = dx/len, uy = dy/len;
        float tipX = from.x + ux*22*dp, tipY = from.y + uy*22*dp;
        float al = 14*dp, aw = 7*dp;
        Path path = new Path();
        path.moveTo(tipX, tipY);
        path.lineTo(tipX - ux*al + uy*aw, tipY - uy*al - ux*aw);
        path.lineTo(tipX - ux*al - uy*aw, tipY - uy*al + ux*aw);
        path.close();
        canvas.drawPath(path, arrowPaint);
    }

    private void drawPolyline(Canvas c, List<PointF> pts, Paint p) {
        for (int i = 1; i < pts.size(); i++) {
            float x0 = pts.get(i-1).x, y0 = pts.get(i-1).y;
            float x1 = pts.get(i).x,   y1 = pts.get(i).y;
            if (isFinite(x0,y0,x1,y1)) c.drawLine(x0,y0,x1,y1,p);
        }
    }

    private static boolean isFinite(float... v) {
        for (float f : v) if (Float.isNaN(f)||Float.isInfinite(f)) return false;
        return true;
    }

    // ── Paint helpers ─────────────────────────────────────────────────────────

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w*dp); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND); return p;
    }
    private Paint strokeA(int color, float w, int alpha) {
        Paint p = stroke(color, w); p.setAlpha(alpha); return p;
    }
    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL); return p;
    }
}

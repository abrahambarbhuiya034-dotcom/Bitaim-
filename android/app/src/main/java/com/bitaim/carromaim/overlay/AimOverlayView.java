package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AimOverlayView — v3
 *
 * Fully automatic overlay — no touch input required.
 * - Positions are EMA-smoothed to eliminate jitter.
 * - Auto-computes the top 5 best shots (coin → nearest pocket).
 * - Draws a max of 5 prediction lines inside the board bounds.
 * - Watermark "created by abraham / Xhay" at board centre.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES  = 5;
    private static final float EMA_ALPHA  = 0.20f;   // lower = smoother (less jitter)

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private final float dp;

    private final Paint aimPaint, bouncePaint, bounce2Paint;
    private final Paint coinPathPaint, pocketPathPaint;
    private final Paint strikerPaint, coinOutlinePaint, pocketFill;
    private final Paint boardPaint;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint textPaint, watermarkPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        aimPaint        = stroke(0xFFFFD700, 3.5f);   // gold  — direct aim
        bouncePaint     = stroke(0xFF00E5FF, 2.8f);   // cyan  — 1-wall bounce
        bounce2Paint    = stroke(0xFFD946EF, 2.8f);   // magenta — 2-wall bounce
        coinPathPaint   = stroke(0xFFFF8A00, 3.0f);   // orange — coin roll path
        pocketPathPaint = stroke(0xFF22C55E, 4.0f);   // green — into pocket

        strikerPaint     = stroke(0xFFFFD700, 2.2f);
        coinOutlinePaint = stroke(0x88FFFFFF, 1.5f);
        pocketFill       = fill(0x882ECC71);
        boardPaint       = stroke(0x44FFD700, 1.2f);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));

        blackFill = fill(0x55000000);
        whiteFill = fill(0x44FFFFFF);
        redFill   = fill(0x55FF3D71);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(12 * dp);
        textPaint.setShadowLayer(2 * dp, 0, 0, Color.BLACK);

        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x33FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);
        watermarkPaint.setShadowLayer(1 * dp, 0, 0, Color.BLACK);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND); return p;
    }
    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL); return p;
    }

    public void setShotMode(String mode) { this.shotMode = mode; postInvalidate(); }
    public void setMarginOffset(float dx, float dy) { /* auto calibrated */ }
    public void setSensitivity(float v) { /* no longer used */ }

    public void setDetectedState(GameState s) {
        if (s == null) return;
        detected = s;
        applySmoothing(s);
        postInvalidate();
    }

    // ── EMA smoothing ────────────────────────────────────────────────────────

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
        out.coins   = raw.coins;
        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }
    private float ema(float p, float n) { return p + EMA_ALPHA * (n - p); }

    // ── Draw ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        // Board outline + watermark
        if (s.board != null) {
            canvas.drawRect(s.board, boardPaint);
            canvas.drawText("created by abraham / Xhay",
                    s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        // Pockets
        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, 13 * dp, pocketFill);

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

        // Top-5 auto aim shots
        List<ShotCandidate> shots = computeBestShots(s);
        int drawn = 0;
        for (ShotCandidate shot : shots) {
            if (drawn >= MAX_LINES) break;
            drawShot(canvas, s, shot);
            drawn++;
        }
    }

    // ── Shot candidates ───────────────────────────────────────────────────────

    private static class ShotCandidate {
        final PointF ghostPos;
        final Coin   coin;
        final PointF pocket;
        final float  score;
        ShotCandidate(PointF g, Coin c, PointF pk, float sc) {
            ghostPos = g; coin = c; pocket = pk; score = sc;
        }
    }

    private List<ShotCandidate> computeBestShots(GameState s) {
        List<ShotCandidate> list = new ArrayList<>();
        if (s.pockets.isEmpty()) return list;

        for (Coin coin : s.coins) {
            if (coin.color == Coin.COLOR_STRIKER) continue;

            // Find nearest pocket for this coin
            PointF bestPocket = null;
            float  bestDist   = Float.MAX_VALUE;
            for (PointF pk : s.pockets) {
                float d = dist(coin.pos, pk);
                if (d < bestDist) { bestDist = d; bestPocket = pk; }
            }
            if (bestPocket == null) continue;

            // Ghost-ball position: striker must arrive here to send coin into pocket
            float dx = coin.pos.x - bestPocket.x;
            float dy = coin.pos.y - bestPocket.y;
            float len = (float) Math.sqrt(dx*dx + dy*dy);
            if (len < 1f) continue;
            float ghostR = s.striker.radius + coin.radius;
            PointF ghost = new PointF(
                    coin.pos.x + (dx / len) * ghostR,
                    coin.pos.y + (dy / len) * ghostR);

            // Keep ghost inside board bounds
            if (s.board != null && !s.board.contains(ghost.x, ghost.y)) continue;

            float score = 800f / (dist(s.striker.pos, ghost) + 1f)
                        + 400f / (bestDist + 1f);

            // Queen (red) gets priority boost
            if (coin.color == Coin.COLOR_RED) score *= 1.4f;

            list.add(new ShotCandidate(ghost, coin, bestPocket, score));
        }

        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));
        return list;
    }

    private void drawShot(Canvas canvas, GameState s, ShotCandidate shot) {
        // Aim line: striker → ghost position
        canvas.drawLine(s.striker.pos.x, s.striker.pos.y,
                shot.ghostPos.x, shot.ghostPos.y, aimPaint);

        // Ghost ball circle at contact point
        canvas.drawCircle(shot.ghostPos.x, shot.ghostPos.y,
                s.striker.radius, coinOutlinePaint);

        // Coin-to-pocket line
        if (shot.coin != null && shot.pocket != null) {
            canvas.drawLine(shot.coin.pos.x, shot.coin.pos.y,
                    shot.pocket.x, shot.pocket.y, coinPathPaint);
            canvas.drawCircle(shot.pocket.x, shot.pocket.y, 16*dp, pocketPathPaint);
        }

        // Physics simulation for striker path after contact
        List<TrajectorySimulator.PathSegment> segs = simulator.simulate(
                s.striker, shot.ghostPos, s.coins, s.pockets, s.board, 1.0f);
        int segDrawn = 0;
        for (TrajectorySimulator.PathSegment seg : segs) {
            if (segDrawn >= 2) break;
            drawPolyline(canvas, seg.points, paintForSeg(seg));
            segDrawn++;
        }
    }

    private Paint paintForSeg(TrajectorySimulator.PathSegment seg) {
        if (seg.enteredPocket)  return pocketPathPaint;
        if (seg.wallBounces == 0) return aimPaint;
        if (seg.wallBounces == 1) return bouncePaint;
        return bounce2Paint;
    }

    private void drawPolyline(Canvas c, List<PointF> pts, Paint p) {
        for (int i = 1; i < pts.size(); i++)
            c.drawLine(pts.get(i-1).x, pts.get(i-1).y, pts.get(i).x, pts.get(i).y, p);
    }

    private float dist(PointF a, PointF b) {
        float dx = a.x-b.x, dy = a.y-b.y;
        return (float) Math.sqrt(dx*dx+dy*dy);
    }
}

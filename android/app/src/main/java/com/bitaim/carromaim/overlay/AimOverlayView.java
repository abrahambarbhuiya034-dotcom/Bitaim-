package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.BlurMaskFilter;
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
 * AimOverlayView v7
 *
 * Visual aim lines on top of the carrom game screen.
 *
 * Lines:
 *   White glow  → striker direct path to ghost-ball contact point
 *   Dotted blue → predicted coin path after hit (toward pocket)
 *   Cyan        → striker path after 1-wall bounce
 *   Magenta     → striker path after 2-wall bounces
 *   Green ring  → pocket the coin will enter
 *
 * Algorithm: Ghost-ball method.
 *   For each coin, compute the ghost-ball position (where striker must be
 *   to send the coin toward the nearest pocket), draw a line from striker
 *   to ghost, then from coin to pocket.  Top 5 shots by score are shown.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES  = 5;
    private static final float EMA_ALPHA  = 0.22f;

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private final float dp;

    // Aim line paints
    private final Paint strikerLinePaint;   // white glow — striker direct path
    private final Paint strikerGlowPaint;   // wider white blur for glow
    private final Paint bouncePaint;        // cyan — 1-wall bounce
    private final Paint bounce2Paint;       // magenta — 2-wall bounce
    private final Paint coinPathPaint;      // dotted blue — coin predicted path
    private final Paint pocketRingPaint;    // green ring — target pocket

    // Piece rendering
    private final Paint ghostCirclePaint;   // white dashed circle at ghost-ball position
    private final Paint strikerFillPaint;
    private final Paint blackFill, whiteFill, redFill;
    private final Paint coinOutlinePaint;
    private final Paint pocketFill;
    private final Paint boardPaint;

    // Text
    private final Paint watermarkPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        // Striker direct-path: solid white with soft glow
        strikerLinePaint = stroke(0xFFFFFFFF, 3.2f);

        strikerGlowPaint = stroke(0x66FFFFFF, 9f);
        strikerGlowPaint.setMaskFilter(new BlurMaskFilter(8 * dp, BlurMaskFilter.Blur.NORMAL));

        // Bounce paths
        bouncePaint  = stroke(0xFF00E5FF, 2.6f);
        bounce2Paint = stroke(0xFFD946EF, 2.6f);

        // Coin predicted path: dotted blue
        coinPathPaint = stroke(0xFF4488FF, 2.8f);
        coinPathPaint.setPathEffect(new DashPathEffect(new float[]{10*dp, 6*dp}, 0));

        // Pocket ring: glowing green
        pocketRingPaint = stroke(0xFF22C55E, 3.0f);
        pocketRingPaint.setMaskFilter(new BlurMaskFilter(5 * dp, BlurMaskFilter.Blur.NORMAL));

        // Ghost circle at contact point
        ghostCirclePaint = stroke(0xCCFFFFFF, 1.6f);
        ghostCirclePaint.setPathEffect(new DashPathEffect(new float[]{5*dp, 4*dp}, 0));

        strikerFillPaint = fill(0x55FFFFFF);
        blackFill        = fill(0x55000000);
        whiteFill        = fill(0x44FFFFFF);
        redFill          = fill(0x55FF3D71);
        coinOutlinePaint = stroke(0x88FFFFFF, 1.2f);
        pocketFill       = fill(0x882ECC71);

        boardPaint = stroke(0x33FFD700, 1.0f);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));

        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x22FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);

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
    public void setMarginOffset(float dx, float dy) {}
    public void setSensitivity(float v) {}

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
        out.coins   = raw.coins.isEmpty() && smoothed.coins != null
                    ? smoothed.coins : raw.coins;
        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }
    private float ema(float p, float n) { return p + EMA_ALPHA * (n - p); }

    // ── onDraw ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        // Board outline
        if (s.board != null) {
            canvas.drawRect(s.board, boardPaint);
            canvas.drawText("created by abraham / Xhay",
                    s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        // Pocket indicators
        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, 12 * dp, pocketFill);

        // Coins
        for (Coin c : s.coins) {
            Paint f = c.color == Coin.COLOR_BLACK ? blackFill
                    : c.color == Coin.COLOR_RED   ? redFill : whiteFill;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, f);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }

        // Striker
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerFillPaint);

        // Aim shots
        List<ShotCandidate> shots = computeBestShots(s);
        int drawn = 0;
        for (ShotCandidate shot : shots) {
            if (drawn >= MAX_LINES) break;
            drawShot(canvas, s, shot, drawn == 0);
            drawn++;
        }
    }

    // ── Ghost-ball shot candidates ───────────────────────────────────────────

    private static class ShotCandidate {
        final PointF ghostPos;
        final Coin   coin;
        final PointF pocket;
        final float  score;
        ShotCandidate(PointF g, Coin c, PointF pk, float sc) {
            ghostPos = g; coin = c; pocket = pk; score = sc;
        }
    }

    List<ShotCandidate> computeBestShots(GameState s) {
        List<ShotCandidate> list = new ArrayList<>();
        if (s.pockets.isEmpty() || s.coins.isEmpty()) return list;

        for (Coin coin : s.coins) {
            if (coin.color == Coin.COLOR_STRIKER) continue;

            PointF bestPocket = null;
            float  bestDist   = Float.MAX_VALUE;
            for (PointF pk : s.pockets) {
                float d = dist(coin.pos, pk);
                if (d < bestDist) { bestDist = d; bestPocket = pk; }
            }
            if (bestPocket == null) continue;

            float dx  = coin.pos.x - bestPocket.x;
            float dy  = coin.pos.y - bestPocket.y;
            float len = (float) Math.sqrt(dx*dx + dy*dy);
            if (len < 1f) continue;

            float ghostR = s.striker.radius + coin.radius;
            PointF ghost = new PointF(
                    coin.pos.x + (dx / len) * ghostR,
                    coin.pos.y + (dy / len) * ghostR);

            // Allow ghost to be slightly outside board (1 coin radius tolerance)
            if (s.board != null) {
                RectF expanded = new RectF(
                        s.board.left  - coin.radius,
                        s.board.top   - coin.radius,
                        s.board.right + coin.radius,
                        s.board.bottom+ coin.radius);
                if (!expanded.contains(ghost.x, ghost.y)) continue;
            }

            float score = 700f / (dist(s.striker.pos, ghost) + 1f)
                        + 350f / (bestDist + 1f);
            if (coin.color == Coin.COLOR_RED) score *= 1.4f;

            list.add(new ShotCandidate(ghost, coin, bestPocket, score));
        }

        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));
        return list;
    }

    private void drawShot(Canvas canvas, GameState s, ShotCandidate shot, boolean isBest) {
        // Glow on the best shot only
        if (isBest) {
            canvas.drawLine(s.striker.pos.x, s.striker.pos.y,
                    shot.ghostPos.x, shot.ghostPos.y, strikerGlowPaint);
        }
        // Solid white striker → ghost line
        canvas.drawLine(s.striker.pos.x, s.striker.pos.y,
                shot.ghostPos.x, shot.ghostPos.y, strikerLinePaint);

        // Ghost circle at contact point
        canvas.drawCircle(shot.ghostPos.x, shot.ghostPos.y, s.striker.radius, ghostCirclePaint);

        // Dotted blue coin → pocket path
        if (shot.coin != null && shot.pocket != null) {
            canvas.drawLine(shot.coin.pos.x, shot.coin.pos.y,
                    shot.pocket.x, shot.pocket.y, coinPathPaint);
            // Green glowing ring at target pocket
            canvas.drawCircle(shot.pocket.x, shot.pocket.y, 18 * dp, pocketRingPaint);
        }

        // Physics prediction for striker path after contact (bounce lines)
        List<TrajectorySimulator.PathSegment> segs = simulator.simulate(
                s.striker, shot.ghostPos, s.coins, s.pockets, s.board, 1.0f);
        int segDrawn = 0;
        for (TrajectorySimulator.PathSegment seg : segs) {
            if (segDrawn >= 2) break;
            if (seg.wallBounces > 0)
                drawPolyline(canvas, seg.points,
                        seg.wallBounces == 1 ? bouncePaint : bounce2Paint);
            segDrawn++;
        }
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

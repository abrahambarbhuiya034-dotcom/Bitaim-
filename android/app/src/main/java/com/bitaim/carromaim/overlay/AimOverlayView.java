package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView
 *
 * Transparent fullscreen overlay that draws auto-detected game state
 * (striker, coins, pockets, board) plus the predicted trajectories of
 * every moving body when the user picks an aim target.
 *
 * Behavior changes from the original version:
 *   - Striker is LOCKED — touching the striker no longer drags it.
 *     The striker position is whatever the screen-capture detector reports.
 *   - On tap/drag, the touch point becomes the aim target.
 *   - Renders multi-line predictions for ALL shot types simultaneously
 *     (driven by TrajectorySimulator), including coin-on-coin chain
 *     reactions and cushion bounces.
 */
public class AimOverlayView extends View {

    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";
    public static final String MODE_ALL    = "ALL";

    private final TrajectorySimulator simulator = new TrajectorySimulator();

    private String shotMode = MODE_ALL;
    private PointF targetPos;
    private GameState detected;     // latest detected board state
    private float marginOffsetX = 0f, marginOffsetY = 0f;
    private float sensitivity = 1.0f;
    private final float dp;

    // Paints
    private final Paint mainLinePaint, ghostPaint, coinPathPaint, queenPathPaint;
    private final Paint goldenPaint, luckyPaint, pocketPathPaint;
    private final Paint strikerOutlinePaint, coinOutlinePaint, pocketPaint;
    private final Paint targetPaint, anglePaint, textPaint, boardPaint;
    private final Paint blackPaint, whitePaint, redPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        mainLinePaint = stroke(Color.parseColor("#FFD700"), 3.5f); // yellow — striker direct
        ghostPaint    = stroke(Color.parseColor("#CCFFFFFF"), 2.4f);
        ghostPaint.setPathEffect(new DashPathEffect(new float[]{14*dp, 9*dp}, 0));

        coinPathPaint = stroke(Color.parseColor("#FF8A00"), 3.0f); // orange — coin trajectory
        queenPathPaint = stroke(Color.parseColor("#FF3D71"), 3.0f); // pink — queen
        goldenPaint   = stroke(Color.parseColor("#00E5FF"), 2.8f); // cyan — 1-bounce highlight
        luckyPaint    = stroke(Color.parseColor("#D946EF"), 2.8f); // magenta — 2+ bounce
        pocketPathPaint = stroke(Color.parseColor("#22C55E"), 4.0f); // green — leads into pocket

        strikerOutlinePaint = stroke(Color.parseColor("#FFD700"), 2f);
        coinOutlinePaint    = stroke(Color.parseColor("#88FFFFFF"), 1.5f);
        pocketPaint         = fill(Color.parseColor("#882ECC71"));

        targetPaint = stroke(Color.parseColor("#FFFFFFFF"), 2f);
        anglePaint  = stroke(Color.parseColor("#88FFFFFF"), 1.5f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(12 * dp);
        textPaint.setShadowLayer(2 * dp, 0, 0, Color.BLACK);

        boardPaint = stroke(Color.parseColor("#33FFD700"), 1.2f);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));

        whitePaint = fill(Color.parseColor("#33FFFFFF"));
        blackPaint = fill(Color.parseColor("#33000000"));
        redPaint   = fill(Color.parseColor("#33FF3D71"));

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * dp);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }
    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setShotMode(String mode)        { this.shotMode = mode; postInvalidate(); }
    public void setMarginOffset(float dx, float dy) { this.marginOffsetX = dx; this.marginOffsetY = dy; postInvalidate(); }
    public void setSensitivity(float v)         { this.sensitivity = Math.max(0.3f, Math.min(v, 3.0f)); postInvalidate(); }

    /** Called by ScreenCaptureService when a new frame has been processed. */
    public void setDetectedState(GameState s) {
        this.detected = s;
        postInvalidate();
    }

    // ── Touch — aim target only; striker is locked ───────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                targetPos = new PointF(
                        event.getX() + marginOffsetX,
                        event.getY() + marginOffsetY);
                postInvalidate();
                return true;
            case MotionEvent.ACTION_UP:
                postInvalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        GameState s = detected;
        // Fall back to a synthetic state so the UI is usable before screen capture is granted.
        if (s == null) s = synthFallback();
        if (s == null || s.striker == null) {
            drawHint(canvas, "Waiting for board detection…");
            return;
        }

        // Board outline (debug visual)
        if (s.board != null) {
            canvas.drawRect(s.board, boardPaint);
        }

        // Pockets
        for (PointF p : s.pockets) {
            canvas.drawCircle(p.x, p.y, 14 * dp, pocketPaint);
        }

        // Detected coins
        for (Coin c : s.coins) {
            Paint fill = (c.color == Coin.COLOR_BLACK) ? blackPaint
                       : (c.color == Coin.COLOR_RED)   ? redPaint
                                                       : whitePaint;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, fill);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
        }

        // Striker (locked indicator — show it, but no drag)
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, whitePaint);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerOutlinePaint);

        if (targetPos == null) {
            drawHint(canvas, "Tap on the board to set aim target");
            return;
        }

        // Target marker
        canvas.drawCircle(targetPos.x, targetPos.y, 9 * dp, targetPaint);
        canvas.drawLine(targetPos.x - 14*dp, targetPos.y, targetPos.x + 14*dp, targetPos.y, targetPaint);
        canvas.drawLine(targetPos.x, targetPos.y - 14*dp, targetPos.x, targetPos.y + 14*dp, targetPaint);

        // Direct aim line — always drawn
        canvas.drawLine(s.striker.pos.x, s.striker.pos.y, targetPos.x, targetPos.y, mainLinePaint);
        drawAngleLabel(canvas, s.striker.pos, targetPos);

        // Run physics simulation
        List<TrajectorySimulator.PathSegment> paths = simulator.simulate(
                s.striker, targetPos, s.coins, s.pockets, s.board, sensitivity);

        // Render each segment based on mode filter + bounce count
        for (TrajectorySimulator.PathSegment seg : paths) {
            if (!shouldDraw(seg)) continue;
            Paint paint = paintFor(seg);
            drawPolyline(canvas, seg.points, paint);
            if (seg.enteredPocket && !seg.points.isEmpty()) {
                PointF end = seg.points.get(seg.points.size() - 1);
                canvas.drawCircle(end.x, end.y, 18 * dp, pocketPathPaint);
            }
        }
    }

    private boolean shouldDraw(TrajectorySimulator.PathSegment seg) {
        if (MODE_ALL.equals(shotMode))    return true;
        if (MODE_DIRECT.equals(shotMode)) return seg.kind == 0 && seg.wallBounces == 0;
        if (MODE_AI.equals(shotMode))     return true;
        if (MODE_GOLDEN.equals(shotMode)) return seg.wallBounces <= 1;
        if (MODE_LUCKY.equals(shotMode))  return seg.wallBounces <= 2;
        return true;
    }

    private Paint paintFor(TrajectorySimulator.PathSegment seg) {
        if (seg.enteredPocket)  return pocketPathPaint;
        if (seg.kind == 0)      return seg.wallBounces == 0 ? ghostPaint
                                  : (seg.wallBounces == 1 ? goldenPaint : luckyPaint);
        if (seg.kind == 3)      return queenPathPaint;
        return coinPathPaint;
    }

    private void drawPolyline(Canvas c, List<PointF> pts, Paint p) {
        if (pts.size() < 2) return;
        for (int i = 1; i < pts.size(); i++) {
            PointF a = pts.get(i - 1), b = pts.get(i);
            c.drawLine(a.x, a.y, b.x, b.y, p);
        }
    }

    private void drawAngleLabel(Canvas canvas, PointF from, PointF to) {
        double angle = Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
        if (angle < 0) angle += 360;
        canvas.drawText(String.format("%.1f°", angle),
                (from.x + to.x) / 2f + 10 * dp,
                (from.y + to.y) / 2f - 8 * dp,
                textPaint);
    }

    private void drawHint(Canvas canvas, String msg) {
        canvas.drawText(msg, 24 * dp, 60 * dp, textPaint);
    }

    /**
     * Synthesize a placeholder GameState before MediaProjection is granted, so the
     * overlay still shows something useful when the user is just demoing the app.
     * Once real detection kicks in, this is overwritten on the next frame.
     */
    private GameState synthFallback() {
        if (getWidth() == 0 || getHeight() == 0) return null;
        GameState s = new GameState();
        int w = getWidth(), h = getHeight();
        float side = Math.min(w, h) * 0.92f;
        float cx = w / 2f, cy = h / 2f;
        s.board = new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);
        s.pockets.add(new PointF(s.board.left + 24*dp, s.board.top + 24*dp));
        s.pockets.add(new PointF(s.board.right - 24*dp, s.board.top + 24*dp));
        s.pockets.add(new PointF(s.board.left + 24*dp, s.board.bottom - 24*dp));
        s.pockets.add(new PointF(s.board.right - 24*dp, s.board.bottom - 24*dp));
        // Place a few demo coins around center
        s.coins = new ArrayList<>();
        float r = 14 * dp;
        for (int i = -2; i <= 2; i++) {
            for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) continue;
                int color = ((i + j) & 1) == 0 ? Coin.COLOR_BLACK : Coin.COLOR_WHITE;
                s.coins.add(new Coin(cx + i * 38*dp, cy + j * 38*dp, r, color, false));
            }
        }
        s.coins.add(new Coin(cx, cy, r, Coin.COLOR_RED, false));
        s.striker = new Coin(cx, s.board.bottom - 80*dp, 16*dp, Coin.COLOR_STRIKER, true);
        return s;
    }
}

package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * BoardDetector — v3
 *
 * Improvements:
 *  1. Auto board detection via wood-colour mask — only the inner square
 *     playing surface (warm tan/orange, H 8-25 HSV) is considered board.
 *  2. Coin detection is restricted to within the detected board rectangle,
 *     eliminating false positives from UI elements (avatars, score circles).
 *  3. Board rect is EMA-smoothed across frames for stability.
 *  4. Coins classified as BLACK (dark), WHITE (light), or RED (queen) only.
 */
public class BoardDetector {

    private static final String TAG       = "BoardDetector";
    private static final int    PROC_W    = 640;
    private static final float  EMA_BOARD = 0.15f;   // board rect smoothing

    private float  minRadiusFrac = 0.013f;
    private float  maxRadiusFrac = 0.042f;
    private double param2        = 20;

    private RectF  smoothedBoard = null;   // persists across frames

    private final Mat frame   = new Mat();
    private final Mat small   = new Mat();
    private final Mat gray    = new Mat();
    private final Mat hsv     = new Mat();
    private final Mat circles = new Mat();

    public void setMinRadiusFrac(float v) { minRadiusFrac = Math.max(0.005f, Math.min(v, 0.05f)); }
    public void setMaxRadiusFrac(float v) { maxRadiusFrac = Math.max(0.02f,  Math.min(v, 0.10f)); }
    public void setParam2(double v)       { param2 = Math.max(10, Math.min(v, 60)); }

    public synchronized GameState detect(Bitmap bitmap) {
        if (bitmap == null) return null;
        int srcW = bitmap.getWidth(), srcH = bitmap.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        Utils.bitmapToMat(bitmap, frame);
        float scale = (float) PROC_W / srcW;
        int   procH = Math.round(srcH * scale);
        Imgproc.resize(frame, small, new Size(PROC_W, procH), 0, 0, Imgproc.INTER_AREA);
        Imgproc.cvtColor(small, hsv,  Imgproc.COLOR_RGB2HSV);
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.medianBlur(gray, gray, 5);

        // ── 1. Detect board by wood colour ──────────────────────────────────
        RectF rawBoard = detectBoardRect(srcW, srcH, scale);
        smoothedBoard  = smoothRect(smoothedBoard, rawBoard);
        RectF board    = smoothedBoard;

        // ── 2. Restrict HoughCircles to inner board area ────────────────────
        Rect roiRect = null;
        Mat  roiGray = gray, roiHsv = hsv;
        float roiOffX = 0, roiOffY = 0;
        if (board != null) {
            // Shrink board by ~6% to exclude the cushion/pocket areas
            float inset = board.width() * 0.06f;
            int rx = Math.max(0, Math.round((board.left + inset) * scale));
            int ry = Math.max(0, Math.round((board.top  + inset) * scale));
            int rw = Math.min(PROC_W  - rx, Math.round((board.width()  - 2*inset) * scale));
            int rh = Math.min(procH   - ry, Math.round((board.height() - 2*inset) * scale));
            if (rw > 20 && rh > 20) {
                roiRect = new Rect(rx, ry, rw, rh);
                roiGray = gray.submat(roiRect);
                roiHsv  = hsv .submat(roiRect);
                roiOffX = rx;
                roiOffY = ry;
            }
        }

        // ── 3. HoughCircles ──────────────────────────────────────────────────
        int minR    = Math.round(PROC_W * minRadiusFrac);
        int maxR    = Math.round(PROC_W * maxRadiusFrac);
        int minDist = (int)(minR * 1.8);
        Imgproc.HoughCircles(roiGray, circles, Imgproc.HOUGH_GRADIENT,
                1.2, minDist, 100, param2, minR, maxR);

        GameState state = new GameState();
        List<Coin>  all = new ArrayList<>();

        if (!circles.empty()) {
            int n = circles.cols();
            for (int i = 0; i < n; i++) {
                double[] c = circles.get(0, i);
                if (c == null || c.length < 3) continue;
                float cx = (float)c[0], cy = (float)c[1], cr = (float)c[2];
                int colorClass = classifyColor(roiHsv, (int)cx, (int)cy, (int)cr);
                if (colorClass < 0) continue;
                // Map back to screen coords (ROI offset + scale)
                float scx = (cx + roiOffX) / scale;
                float scy = (cy + roiOffY) / scale;
                float scr = cr / scale;
                all.add(new Coin(scx, scy, scr, colorClass, false));
            }
        }
        if (roiRect != null) { roiGray.release(); roiHsv.release(); }

        // ── 4. Identify striker (largest white circle in lower 40%) ─────────
        float lowerThreshold = srcH * 0.55f;
        Coin  striker = null;
        float strikerScore = -1;
        for (Coin c : all) {
            if (c.color != Coin.COLOR_WHITE) continue;
            float yBonus = (c.pos.y > lowerThreshold) ? 2.0f : 1.0f;
            float score  = c.radius * yBonus;
            if (score > strikerScore) { strikerScore = score; striker = c; }
        }
        if (striker != null) {
            striker.isStriker = true;
            striker.color     = Coin.COLOR_STRIKER;
            state.striker     = striker;
        }
        for (Coin c : all) {
            if (c != striker) state.coins.add(c);
        }

        // ── 5. Board + pockets ───────────────────────────────────────────────
        state.board = board != null ? board : fallbackBoard(srcW, srcH);
        addPockets(state);
        return state;
    }

    // ── Board detection via wood-colour mask ─────────────────────────────────

    /**
     * Find the bounding rectangle of the warm wood-coloured region in the
     * downscaled frame. The carrom board has a distinctive tan/orange wood
     * colour (H 8–28, S 40–200, V 90–220 in OpenCV HSV).
     */
    private RectF detectBoardRect(int srcW, int srcH, float scale) {
        Mat woodMask = new Mat();
        // Wood colour range (handles both lighter and darker board themes)
        Core.inRange(hsv,
                new Scalar(8,  35,  80),   // lower bound (H, S, V)
                new Scalar(28, 210, 225),  // upper bound
                woodMask);

        // Morphological close to fill small gaps
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
        Imgproc.morphologyEx(woodMask, woodMask, Imgproc.MORPH_CLOSE, kernel);
        Imgproc.morphologyEx(woodMask, woodMask, Imgproc.MORPH_OPEN,  kernel);
        kernel.release();

        // Find largest contour
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(woodMask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();
        woodMask.release();

        double bestArea = 0;
        Rect   bestRect = null;
        for (MatOfPoint c : contours) {
            Rect r = Imgproc.boundingRect(c);
            double area = r.width * (double) r.height;
            if (area > bestArea) { bestArea = area; bestRect = r; }
            c.release();
        }

        if (bestRect == null || bestArea < 0.04 * PROC_W * PROC_W) {
            return null; // Not enough wood detected — use fallback
        }

        // Convert to screen coords, force square
        float l = bestRect.x / scale, t = bestRect.y / scale;
        float r = (bestRect.x + bestRect.width)  / scale;
        float b = (bestRect.y + bestRect.height) / scale;

        // Make it square (take the larger dimension)
        float side = Math.max(r - l, b - t);
        float cx   = (l + r) / 2f, cy = (t + b) / 2f;
        float nl = cx - side/2f, nt = cy - side/2f;
        float nr = cx + side/2f, nb = cy + side/2f;

        // Clamp into screen
        nl = Math.max(0, nl); nt = Math.max(0, nt);
        nr = Math.min(srcW, nr); nb = Math.min(srcH, nb);
        return new RectF(nl, nt, nr, nb);
    }

    // ── Colour classification ─────────────────────────────────────────────────

    private int classifyColor(Mat hsvMat, int x, int y, int r) {
        if (r < 2) return -1;
        if (x - r < 0 || y - r < 0 || x + r >= hsvMat.cols() || y + r >= hsvMat.rows()) return -1;

        int s = Math.max(1, r / 4);
        Mat patch = hsvMat.submat(
                Math.max(0, y-s), Math.min(hsvMat.rows(), y+s),
                Math.max(0, x-s), Math.min(hsvMat.cols(), x+s));
        Scalar mean = Core.mean(patch);
        patch.release();

        double h = mean.val[0]; // 0..180
        double sat = mean.val[1]; // 0..255
        double v = mean.val[2]; // 0..255

        // Black coin — very dark
        if (v < 65) return Coin.COLOR_BLACK;
        // White coin / striker — bright and low saturation
        if (v > 170 && sat < 65) return Coin.COLOR_WHITE;
        // Red queen — reddish hue, saturated
        if (sat > 85 && (h < 14 || h > 162)) return Coin.COLOR_RED;
        // Wood tone — reject (board surface)
        if (h >= 8 && h <= 28 && sat > 35) return -1;

        return -1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RectF fallbackBoard(int w, int h) {
        // Fallback: assume board is centred, occupying ~70% of width
        float side = w * 0.70f;
        float cx = w / 2f, cy = h * 0.50f;
        return new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);
    }

    private void addPockets(GameState state) {
        if (state.board == null) return;
        float inset = state.board.width() * 0.03f;
        state.pockets.add(new PointF(state.board.left  + inset, state.board.top    + inset));
        state.pockets.add(new PointF(state.board.right - inset, state.board.top    + inset));
        state.pockets.add(new PointF(state.board.left  + inset, state.board.bottom - inset));
        state.pockets.add(new PointF(state.board.right - inset, state.board.bottom - inset));
    }

    private RectF smoothRect(RectF prev, RectF next) {
        if (prev == null) return next;
        if (next == null) return prev; // keep last known board
        float a = EMA_BOARD;
        return new RectF(
            prev.left   + a*(next.left   - prev.left),
            prev.top    + a*(next.top    - prev.top),
            prev.right  + a*(next.right  - prev.right),
            prev.bottom + a*(next.bottom - prev.bottom));
    }
}

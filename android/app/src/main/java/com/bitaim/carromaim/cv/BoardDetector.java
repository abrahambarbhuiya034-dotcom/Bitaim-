package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * BoardDetector
 *
 * Runs OpenCV HoughCircles on each captured frame to find the striker and coins,
 * classifies each circle's color (white/black/red/striker), and infers the board
 * rectangle + pocket positions from the detected layout.
 *
 * The pipeline is intentionally tuned for portrait Carrom Pool:
 *   1. downscale frame to ~640 wide (perf)
 *   2. blur + grayscale
 *   3. HoughCircles to find all circular pieces
 *   4. classify each via mean HSV at the center
 *   5. pick the largest white circle on the lower half as striker
 *   6. infer board bounds from the spread of detected coins (or default to portrait square)
 *   7. place 4 corner pockets at the board corners
 *
 * Tuning sliders live in shared preferences via setMinRadius/setMaxRadius.
 */
public class BoardDetector {

    private static final String TAG = "BoardDetector";

    /** Target processing width in pixels. Smaller = faster, less accurate. */
    private static final int PROC_WIDTH = 640;

    /** HoughCircles tunables — overridable at runtime. */
    private float minRadiusFrac = 0.012f; // fraction of frame width
    private float maxRadiusFrac = 0.045f;
    private double param2 = 22;            // accumulator threshold (lower = more circles)

    private final Mat frame = new Mat();
    private final Mat gray = new Mat();
    private final Mat hsv = new Mat();
    private final Mat circles = new Mat();

    public void setMinRadiusFrac(float v) { this.minRadiusFrac = Math.max(0.005f, Math.min(v, 0.05f)); }
    public void setMaxRadiusFrac(float v) { this.maxRadiusFrac = Math.max(0.02f, Math.min(v, 0.10f)); }
    public void setParam2(double v)       { this.param2 = Math.max(10, Math.min(v, 60)); }

    /**
     * Process one captured frame. The bitmap is in screen coords; this method
     * scales to PROC_WIDTH internally and scales output back to screen coords.
     */
    public synchronized GameState detect(Bitmap bitmap) {
        if (bitmap == null) return null;

        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        // Convert to Mat
        Utils.bitmapToMat(bitmap, frame);

        // Downscale for processing speed
        float scale = (float) PROC_WIDTH / srcW;
        int procW = PROC_WIDTH;
        int procH = Math.round(srcH * scale);
        Mat small = new Mat();
        Imgproc.resize(frame, small, new Size(procW, procH), 0, 0, Imgproc.INTER_AREA);

        // Grayscale + blur for HoughCircles
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.medianBlur(gray, gray, 5);

        // HSV for color classification
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV);

        // HoughCircles
        int minR = Math.round(procW * minRadiusFrac);
        int maxR = Math.round(procW * maxRadiusFrac);
        int minDist = (int) (minR * 1.8);

        Imgproc.HoughCircles(
                gray, circles, Imgproc.HOUGH_GRADIENT,
                1.2, minDist,
                100, param2, minR, maxR
        );

        GameState state = new GameState();
        List<Coin> all = new ArrayList<>();

        if (!circles.empty()) {
            int n = circles.cols();
            for (int i = 0; i < n; i++) {
                double[] c = circles.get(0, i);
                if (c == null || c.length < 3) continue;
                float cx = (float) c[0];
                float cy = (float) c[1];
                float cr = (float) c[2];
                int colorClass = classifyColor(hsv, (int) cx, (int) cy, (int) cr);
                if (colorClass < 0) continue;
                // scale back to screen coords
                Coin coin = new Coin(cx / scale, cy / scale, cr / scale,
                        colorClass, false);
                all.add(coin);
            }
        }

        // Identify striker = largest "white-ish" circle on lower half of frame.
        Coin striker = null;
        float strikerScore = -1;
        for (Coin c : all) {
            if (c.color != Coin.COLOR_WHITE && c.color != Coin.COLOR_STRIKER) continue;
            // bottom half preference
            float yWeight = (c.pos.y / srcH); // 0..1, prefer larger
            float score = c.radius * (0.4f + 0.6f * yWeight);
            if (score > strikerScore) {
                strikerScore = score;
                striker = c;
            }
        }
        if (striker != null) {
            striker.isStriker = true;
            striker.color = Coin.COLOR_STRIKER;
            state.striker = striker;
            for (Coin c : all) {
                if (c != striker) state.coins.add(c);
            }
        }

        // Infer board rect from coin spread; fall back to centered square.
        state.board = inferBoardRect(all, srcW, srcH);

        // Place 4 corner pockets at the board's inset corners.
        float pocketInset = state.board.width() * 0.04f;
        state.pockets.add(new PointF(state.board.left + pocketInset,  state.board.top + pocketInset));
        state.pockets.add(new PointF(state.board.right - pocketInset, state.board.top + pocketInset));
        state.pockets.add(new PointF(state.board.left + pocketInset,  state.board.bottom - pocketInset));
        state.pockets.add(new PointF(state.board.right - pocketInset, state.board.bottom - pocketInset));

        small.release();
        return state;
    }

    /**
     * Classify the color at (x,y) by sampling a small inner region of the circle.
     * Returns Coin.COLOR_* constant or -1 to reject.
     */
    private int classifyColor(Mat hsv, int x, int y, int r) {
        if (r < 2) return -1;
        if (x - r < 0 || y - r < 0 || x + r >= hsv.cols() || y + r >= hsv.rows()) return -1;

        // Sample a 3x3 patch at center
        int sample = Math.max(1, r / 3);
        Mat patch = hsv.submat(
                Math.max(0, y - sample), Math.min(hsv.rows(), y + sample),
                Math.max(0, x - sample), Math.min(hsv.cols(), x + sample)
        );
        Scalar mean = Core.mean(patch);
        patch.release();

        double h = mean.val[0]; // 0..180
        double s = mean.val[1]; // 0..255
        double v = mean.val[2]; // 0..255

        // Very dark = black coin
        if (v < 70) return Coin.COLOR_BLACK;
        // Very light, low saturation = white coin / striker
        if (v > 180 && s < 70) return Coin.COLOR_WHITE;
        // Reddish = queen
        if (s > 90 && (h < 12 || h > 165)) return Coin.COLOR_RED;

        // Brownish board background — reject
        if (s < 90 && v > 80 && v < 180 && (h > 10 && h < 30)) return -1;

        // Otherwise reject — likely board surface or UI element
        return -1;
    }

    /**
     * Infer the playable board rectangle from detected coins.
     * If no coins found, default to a centered square of 0.95 × frame width.
     */
    private RectF inferBoardRect(List<Coin> all, int w, int h) {
        if (all.isEmpty()) {
            float side = w * 0.95f;
            float cx = w / 2f, cy = h / 2f;
            return new RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Coin c : all) {
            if (c.pos.x < minX) minX = c.pos.x;
            if (c.pos.y < minY) minY = c.pos.y;
            if (c.pos.x > maxX) maxX = c.pos.x;
            if (c.pos.y > maxY) maxY = c.pos.y;
        }
        // Pad outward by ~6% — coins don't reach the cushion
        float pad = (maxX - minX) * 0.08f;
        if (pad < 20) pad = 20;
        RectF r = new RectF(minX - pad, minY - pad, maxX + pad, maxY + pad);

        // Carrom is roughly square — make it square using the larger side
        float side = Math.max(r.width(), r.height());
        float cx = (r.left + r.right) / 2f;
        float cy = (r.top + r.bottom) / 2f;
        r.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);

        // Clamp into frame
        if (r.left < 0) r.left = 0;
        if (r.top < 0) r.top = 0;
        if (r.right > w) r.right = w;
        if (r.bottom > h) r.bottom = h;
        return r;
    }
}

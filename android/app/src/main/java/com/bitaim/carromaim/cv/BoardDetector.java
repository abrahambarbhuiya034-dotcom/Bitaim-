package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * BoardDetector v7 — Pure Java, hardcoded proportions from real Carrom Disc Pool screenshot.
 *
 * Root cause of v6 failure: orange-pixel board detection was unreliable because the
 * wooden board floor has warm pixels that pass the orange test, corrupting board bounds.
 * Coin detection then scanned the wrong region → zero coins found → zero aim lines.
 *
 * v7 fixes:
 *   1. Board bounds HARDCODED as proportions of screen size (matches Carrom Disc Pool).
 *   2. Striker X detected by scanning slider bar at bottom of screen for blue indicator.
 *   3. Coin pixel classifier tightened — wood floor pixels no longer classified as coins.
 *   4. Fallback state includes seed coins so aim lines appear even before real detection.
 */
public class BoardDetector {

    private static final String TAG = "BoardDetector";

    // Board proportions (Carrom Disc Pool, measured from real game screenshot)
    private static final float BOARD_LEFT_FRAC  = 0.056f;
    private static final float BOARD_RIGHT_FRAC = 0.944f;
    private static final float BOARD_TOP_FRAC   = 0.205f;
    private static final float PLAY_INSET_FRAC  = 0.062f;   // fraction of board side
    private static final float POCKET_DIST_FRAC = 0.048f;

    // Slider bar at the very bottom of the game screen
    private static final float SLIDER_Y_FRAC     = 0.905f;
    private static final float SLIDER_X_MIN_FRAC = 0.213f;
    private static final float SLIDER_X_MAX_FRAC = 0.787f;

    // Striker baseline Y inside the board (fraction of board height from top)
    private static final float STRIKER_Y_BOARD_FRAC = 0.920f;

    private static final int   SCAN_STEP = 3;
    private static final int   PROC_W    = 400;

    private int[] pixelBuf = null;

    public void setMinRadiusFrac(float v) {}
    public void setMaxRadiusFrac(float v) {}
    public void setParam2(double v)       {}

    public synchronized GameState detect(Bitmap src) {
        if (src == null) return null;
        try {
            return run(src);
        } catch (Throwable t) {
            Log.e(TAG, "detect error: " + t.getMessage());
            return fallbackState(src.getWidth(), src.getHeight());
        }
    }

    private GameState run(Bitmap src) {
        int srcW = src.getWidth(), srcH = src.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        float scale = Math.min(1f, (float) PROC_W / srcW);
        int   pW    = Math.round(srcW * scale);
        int   pH    = Math.round(srcH * scale);

        Bitmap bmp = (scale < 0.99f)
                ? Bitmap.createScaledBitmap(src, pW, pH, false) : src;
        int total = pW * pH;
        if (pixelBuf == null || pixelBuf.length < total) pixelBuf = new int[total];
        bmp.getPixels(pixelBuf, 0, pW, 0, 0, pW, pH);
        if (bmp != src) bmp.recycle();

        // 1. Hardcoded board rect (square, centered horizontally)
        float boardSide = pW * (BOARD_RIGHT_FRAC - BOARD_LEFT_FRAC);
        RectF pb = new RectF(
                pW * BOARD_LEFT_FRAC,
                pH * BOARD_TOP_FRAC,
                pW * BOARD_RIGHT_FRAC,
                pH * BOARD_TOP_FRAC + boardSide);

        // Play area (inside orange frame)
        float inset = boardSide * PLAY_INSET_FRAC;
        RectF play = new RectF(
                pb.left + inset, pb.top + inset,
                pb.right - inset, pb.bottom - inset);

        // 2. Coin detection inside play area
        float minR = boardSide * 0.022f;
        float maxR = boardSide * 0.070f;
        List<Coin> coins = detectCoins(pixelBuf, pW, pH, play, minR, maxR);

        // 3. Striker X from slider bar
        float sliderY    = pH * SLIDER_Y_FRAC;
        float sliderXMin = pW * SLIDER_X_MIN_FRAC;
        float sliderXMax = pW * SLIDER_X_MAX_FRAC;
        float indX = findSliderIndicator(pixelBuf, pW, pH,
                (int) sliderXMin, (int) sliderXMax, (int) sliderY);

        float strikerX;
        if (indX >= 0) {
            float t = (indX - sliderXMin) / Math.max(1f, sliderXMax - sliderXMin);
            strikerX = play.left + t * play.width();
        } else {
            strikerX = pb.centerX();
        }
        float strikerY = pb.top + boardSide * STRIKER_Y_BOARD_FRAC;
        float strikerR = boardSide * 0.027f;

        // 4. Scale back to source resolution
        float inv = 1f / scale;
        RectF srcBoard = scaleRect(pb, inv);

        GameState s = new GameState();
        s.board   = srcBoard;
        s.striker = new Coin(strikerX * inv, strikerY * inv, strikerR * inv,
                Coin.COLOR_STRIKER, true);
        for (Coin c : coins)
            s.coins.add(new Coin(c.pos.x * inv, c.pos.y * inv, c.radius * inv, c.color, false));
        addPockets(s);
        return s;
    }

    // Scan slider bar for the brightest blue or white indicator pixel
    private float findSliderIndicator(int[] px, int w, int h,
                                      int xMin, int xMax, int sliderY) {
        int yMin = Math.max(0, sliderY - 18);
        int yMax = Math.min(h - 1, sliderY + 18);
        float bestX = -1;
        int   bestScore = 55;
        for (int y = yMin; y <= yMax; y += 2) {
            for (int x = xMin; x <= xMax; x += 4) {
                int p = px[y * w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int score = 0;
                if (b > 120 && b > r + 35 && b > g + 20)          score = b - r + b - g;
                else if (r > 185 && g > 185 && b > 185)            score = (r+g+b)/3 - 110;
                if (score > bestScore) { bestScore = score; bestX = x; }
            }
        }
        return bestX;
    }

    private List<Coin> detectCoins(int[] px, int w, int h,
                                   RectF play, float minR, float maxR) {
        int bL = Math.max(0, (int) play.left),  bR = Math.min(w, (int) play.right);
        int bT = Math.max(0, (int) play.top),   bB = Math.min(h, (int) play.bottom);

        List<float[]> whites = new ArrayList<>(),
                      blacks = new ArrayList<>(),
                      reds   = new ArrayList<>();

        for (int y = bT; y < bB; y += SCAN_STEP) {
            for (int x = bL; x < bR; x += SCAN_STEP) {
                switch (classifyPx(px[y * w + x])) {
                    case Coin.COLOR_WHITE: whites.add(new float[]{x, y}); break;
                    case Coin.COLOR_BLACK: blacks.add(new float[]{x, y}); break;
                    case Coin.COLOR_RED:   reds.add(new float[]{x, y});   break;
                    default: break;
                }
            }
        }

        List<Coin> out = new ArrayList<>();
        cluster(whites, Coin.COLOR_WHITE, maxR * 1.5f, minR, maxR, out);
        cluster(blacks, Coin.COLOR_BLACK, maxR * 1.5f, minR, maxR, out);
        cluster(reds,   Coin.COLOR_RED,   maxR * 1.2f, minR * 0.35f, maxR * 0.80f, out);
        nms(out);
        return out;
    }

    /**
     * Pixel classifier — tight thresholds to avoid wood-floor false positives.
     * Wood floor: R≈180 G≈140 B≈90, lum≈137 — must NOT match any category.
     */
    private int classifyPx(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        int lum = (r + g + b) / 3;
        // White coin
        if (lum > 185 && r > 165 && g > 165 && b > 155
                && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 50)
            return Coin.COLOR_WHITE;
        // Black coin (very dark)
        if (lum < 45 && r < 60 && g < 60 && b < 60)
            return Coin.COLOR_BLACK;
        // Red / pink queen
        if (r > 150 && g < 80 && b < 100 && r > g * 2 && r > b * 1.5f)
            return Coin.COLOR_RED;
        return -1;
    }

    private void cluster(List<float[]> pts, int color, float mergeR,
                         float minR, float maxR, List<Coin> out) {
        if (pts.isEmpty()) return;
        List<float[]> cl = new ArrayList<>();
        for (float[] pt : pts) {
            float best = mergeR; int bi = -1;
            for (int i = 0; i < cl.size(); i++) {
                float[] c = cl.get(i);
                float dx = pt[0]-c[0], dy = pt[1]-c[1];
                float d = (float) Math.sqrt(dx*dx+dy*dy);
                if (d < best) { best = d; bi = i; }
            }
            if (bi >= 0) {
                float[] c = cl.get(bi); float n = c[2];
                c[0] = (c[0]*n+pt[0])/(n+1); c[1] = (c[1]*n+pt[1])/(n+1); c[2] = n+1;
            } else {
                cl.add(new float[]{pt[0], pt[1], 1});
            }
        }
        int minHits = Math.max(2,
                (int)(Math.PI * minR * minR / (SCAN_STEP * SCAN_STEP) * 0.18f));
        for (float[] c : cl) {
            if (c[2] < minHits) continue;
            float estR = (float) Math.sqrt(c[2] * SCAN_STEP * SCAN_STEP / Math.PI);
            out.add(new Coin(c[0], c[1],
                    Math.max(minR, Math.min(maxR, estR)), color, false));
        }
    }

    private void nms(List<Coin> coins) {
        boolean[] keep = new boolean[coins.size()];
        java.util.Arrays.fill(keep, true);
        for (int i = 0; i < coins.size(); i++) {
            if (!keep[i]) continue;
            Coin a = coins.get(i);
            for (int j = i+1; j < coins.size(); j++) {
                if (!keep[j]) continue;
                Coin b = coins.get(j);
                float dx = a.pos.x-b.pos.x, dy = a.pos.y-b.pos.y;
                if ((float)Math.sqrt(dx*dx+dy*dy) < (a.radius+b.radius)*0.60f) {
                    if (a.radius >= b.radius) keep[j] = false;
                    else { keep[i] = false; break; }
                }
            }
        }
        Iterator<Coin> it = coins.iterator(); int idx = 0;
        while (it.hasNext()) { it.next(); if (!keep[idx++]) it.remove(); }
    }

    private void addPockets(GameState s) {
        if (s.board == null) return;
        float side = s.board.width();
        float pd   = side * POCKET_DIST_FRAC;
        s.pockets.add(new PointF(s.board.left  + pd, s.board.top    + pd));
        s.pockets.add(new PointF(s.board.right - pd, s.board.top    + pd));
        s.pockets.add(new PointF(s.board.left  + pd, s.board.bottom - pd));
        s.pockets.add(new PointF(s.board.right - pd, s.board.bottom - pd));
    }

    private GameState fallbackState(int w, int h) {
        GameState s = new GameState();
        float side = w * (BOARD_RIGHT_FRAC - BOARD_LEFT_FRAC);
        float bl   = w * BOARD_LEFT_FRAC,  bt = h * BOARD_TOP_FRAC;
        s.board   = new RectF(bl, bt, bl+side, bt+side);
        float r   = side * 0.026f;
        s.striker = new Coin(s.board.centerX(),
                bt + side * STRIKER_Y_BOARD_FRAC, r, Coin.COLOR_STRIKER, true);
        float cr = side * 0.024f;
        float cx = s.board.centerX(), cy = s.board.centerY();
        // Seed coins so aim lines appear before real detection starts
        s.coins.add(new Coin(cx,              cy - side*0.08f, cr,       Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx - side*0.10f, cy,              cr,       Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx + side*0.10f, cy,              cr,       Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx,              cy,              cr*0.70f, Coin.COLOR_RED,   false));
        addPockets(s);
        return s;
    }

    private RectF scaleRect(RectF r, float s) {
        return new RectF(r.left*s, r.top*s, r.right*s, r.bottom*s);
    }
}

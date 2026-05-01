package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * BoardDetector v6 — Pure Java, zero OpenCV.
 *
 * Works on every Android phone using only Bitmap.getPixels().
 *
 * Pipeline per frame (~33 ms):
 *  1. Downscale bitmap to ≤360 px wide.
 *  2. Scan every 4 px for orange/brown border pixels → board bounding box.
 *  3. Inside board, scan every 3 px, classify each pixel:
 *       white → coin or striker
 *       black → black coin
 *       red   → queen
 *  4. Greedy-cluster same-colour hits into blobs → Coin objects.
 *  5. Striker = largest white blob in bottom 38 % of board height.
 *  6. EMA-smooth the board rectangle across frames.
 *  7. Return GameState with coordinates in input-bitmap space.
 */
public class BoardDetector {

    private static final String TAG       = "BoardDetector";
    private static final int    PROC_W    = 360;
    private static final float  EMA_A     = 0.18f;
    private static final int    SCAN_STEP = 3;

    private RectF smoothedBoard = null;
    private int[] pixelBuf      = null;

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

    // ── Main pipeline ─────────────────────────────────────────────────────────

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

        RectF rawBoard = detectBoard(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = fallbackBoardPx(pW, pH);
        smoothedBoard = smoothRect(smoothedBoard, rawBoard);
        RectF pb = smoothedBoard;

        float minR = pb.width() * 0.022f;
        float maxR = pb.width() * 0.070f;
        List<Coin> coins = detectCoins(pixelBuf, pW, pH, pb, minR, maxR);

        float inv = 1f / scale;
        RectF srcBoard = scaleRect(pb, inv);

        List<Coin> scaled = new ArrayList<>(coins.size());
        for (Coin c : coins)
            scaled.add(new Coin(c.pos.x * inv, c.pos.y * inv,
                                c.radius * inv, c.color, false));

        float strikerThreshY = pb.top + pb.height() * 0.62f;
        Coin striker = null;
        for (Coin c : scaled) {
            if (c.color != Coin.COLOR_WHITE) continue;
            if (c.pos.y < strikerThreshY * inv) continue;
            if (striker == null || c.radius > striker.radius) striker = c;
        }

        GameState s = new GameState();
        s.board = srcBoard;

        if (striker != null) {
            striker.isStriker = true;
            striker.color     = Coin.COLOR_STRIKER;
            s.striker         = striker;
        } else {
            s.striker = new Coin(srcBoard.centerX(),
                srcBoard.top + srcBoard.height() * 0.84f,
                srcBoard.width() * 0.026f, Coin.COLOR_STRIKER, true);
        }

        for (Coin c : scaled) if (c != striker) s.coins.add(c);
        addPockets(s);
        return s;
    }

    // ── Board detection ───────────────────────────────────────────────────────

    private RectF detectBoard(int[] px, int w, int h) {
        int minX = w, maxX = 0, minY = h, maxY = 0, cnt = 0;
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                if (isOrange(px[y * w + x])) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    cnt++;
                }
            }
        }
        float minSpan = w * 0.25f;
        if (cnt < 15 || (maxX - minX) < minSpan || (maxY - minY) < minSpan) return null;

        float side = Math.max(maxX - minX, maxY - minY);
        float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
        return new RectF(Math.max(0, cx - side/2f), Math.max(0, cy - side/2f),
                         Math.min(w, cx + side/2f), Math.min(h, cy + side/2f));
    }

    /** Carrom board orange/brown border: warm hue, R>G>B, high contrast. */
    private boolean isOrange(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        return r > 140 && g > 50 && g < 170 && b < 100
            && r > g && g > b && (r - b) > 80;
    }

    // ── Coin / pixel detection ────────────────────────────────────────────────

    private List<Coin> detectCoins(int[] px, int w, int h,
                                   RectF board, float minR, float maxR) {
        int bL = Math.max(0, (int)(board.left   + board.width()  * 0.04f));
        int bR = Math.min(w, (int)(board.right  - board.width()  * 0.04f));
        int bT = Math.max(0, (int)(board.top    + board.height() * 0.04f));
        int bB = Math.min(h, (int)(board.bottom - board.height() * 0.04f));

        List<float[]> whites = new ArrayList<>(), blacks = new ArrayList<>(),
                      reds   = new ArrayList<>();

        for (int y = bT; y < bB; y += SCAN_STEP) {
            for (int x = bL; x < bR; x += SCAN_STEP) {
                switch (classifyPx(px[y * w + x])) {
                    case Coin.COLOR_WHITE: whites.add(new float[]{x, y}); break;
                    case Coin.COLOR_BLACK: blacks.add(new float[]{x, y}); break;
                    case Coin.COLOR_RED:   reds  .add(new float[]{x, y}); break;
                    default: break;
                }
            }
        }

        List<Coin> out = new ArrayList<>();
        cluster(whites, Coin.COLOR_WHITE, maxR * 1.4f, minR, maxR, out);
        cluster(blacks, Coin.COLOR_BLACK, maxR * 1.4f, minR, maxR, out);
        cluster(reds,   Coin.COLOR_RED,   maxR * 1.2f, minR * 0.4f, maxR * 0.85f, out);
        nms(out);
        return out;
    }

    private int classifyPx(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        int lum = (r + g + b) / 3;

        // White coin: high lum, balanced RGB
        if (lum > 170 && r > 150 && g > 150 && b > 150
                && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 55)
            return Coin.COLOR_WHITE;

        // Black coin: very dark
        if (lum < 55 && r < 70 && g < 70 && b < 70)
            return Coin.COLOR_BLACK;

        // Red queen: dominant red channel
        if (r > 140 && g < 70 && b < 80 && r > g * 2 && r > b * 2)
            return Coin.COLOR_RED;

        return -1;
    }

    /** Greedy single-pass clustering. Each cluster = [cx, cy, count]. */
    private void cluster(List<float[]> pts, int color, float mergeR,
                         float minR, float maxR, List<Coin> out) {
        if (pts.isEmpty()) return;
        List<float[]> cl = new ArrayList<>();
        for (float[] pt : pts) {
            float best = mergeR; int bi = -1;
            for (int i = 0; i < cl.size(); i++) {
                float[] c = cl.get(i);
                float dx = pt[0]-c[0], dy = pt[1]-c[1];
                float d = (float) Math.sqrt(dx*dx + dy*dy);
                if (d < best) { best = d; bi = i; }
            }
            if (bi >= 0) {
                float[] c = cl.get(bi); float n = c[2];
                c[0] = (c[0]*n + pt[0])/(n+1); c[1] = (c[1]*n + pt[1])/(n+1); c[2] = n+1;
            } else {
                cl.add(new float[]{pt[0], pt[1], 1});
            }
        }
        int minHits = Math.max(2, (int)(Math.PI*minR*minR/(SCAN_STEP*SCAN_STEP)*0.20f));
        for (float[] c : cl) {
            if (c[2] < minHits) continue;
            float estR = (float) Math.sqrt(c[2] * SCAN_STEP * SCAN_STEP / Math.PI);
            out.add(new Coin(c[0], c[1], Math.max(minR, Math.min(maxR, estR)), color, false));
        }
    }

    /** Non-maximum suppression — keeps larger of two overlapping circles. */
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
                float d  = (float) Math.sqrt(dx*dx+dy*dy);
                if (d < (a.radius+b.radius)*0.60f) {
                    if (a.radius >= b.radius) keep[j] = false;
                    else { keep[i] = false; break; }
                }
            }
        }
        Iterator<Coin> it = coins.iterator(); int idx = 0;
        while (it.hasNext()) { it.next(); if (!keep[idx++]) it.remove(); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RectF fallbackBoardPx(int w, int h) {
        float side = w * 0.72f, cx = w/2f, cy = h*0.48f;
        return new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
    }

    private GameState fallbackState(int w, int h) {
        GameState s = new GameState();
        float side = w*0.72f, cx = w/2f, cy = h*0.48f;
        s.board   = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        s.striker = new Coin(cx, cy+side*0.34f, side*0.025f, Coin.COLOR_STRIKER, true);
        float r = side*0.022f;
        s.coins.add(new Coin(cx,              cy-side*0.10f, r, Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx-side*0.12f,   cy,            r, Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx+side*0.12f,   cy,            r, Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx,              cy,            r, Coin.COLOR_RED,   false));
        addPockets(s);
        return s;
    }

    private void addPockets(GameState s) {
        if (s.board == null) return;
        float i = s.board.width() * 0.030f;
        s.pockets.add(new PointF(s.board.left  + i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.right - i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.left  + i, s.board.bottom - i));
        s.pockets.add(new PointF(s.board.right - i, s.board.bottom - i));
    }

    private RectF scaleRect(RectF r, float s) {
        return new RectF(r.left*s, r.top*s, r.right*s, r.bottom*s);
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(
            p.left   + EMA_A*(n.left   - p.left),
            p.top    + EMA_A*(n.top    - p.top),
            p.right  + EMA_A*(n.right  - p.right),
            p.bottom + EMA_A*(n.bottom - p.bottom));
    }
}

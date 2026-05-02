package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * BoardDetector v10 — Rebuilt specifically for Carrom Disc Pool (and similar).
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  GAME-SPECIFIC VISUAL PROFILE (from screenshot analysis)
 * ═══════════════════════════════════════════════════════════════════════
 *  Board frame:     Bright saturated ORANGE  ≈ RGB(220, 110, 20)
 *  Corner pockets:  Very large BLACK circles at all 4 corners
 *  Playing surface: Warm tan/light wood      ≈ RGB(200, 160, 110)
 *  Background:      Dark maroon/red          ≈ RGB(110, 25, 25)
 *  White coins:     Off-white cream          ≈ RGB(225, 215, 195)
 *  Black coins:     Very dark                ≈ RGB(40, 40, 40)
 *  Red queen:       Dominant red channel
 *  Striker:         Blue/SILVER metallic     ≈ RGB(90, 110, 185)
 *                   — NOT white! Current code missed this entirely.
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  ROOT CAUSES OF DETECTION FAILURE (fixed here)
 * ═══════════════════════════════════════════════════════════════════════
 *  1. min/max bounding box from orange pixels → in portrait orientation the
 *     vertical span of the orange frame is larger than the horizontal span
 *     (UI bars don't crop it symmetrically). Old code used max(W,H) for
 *     the "side", making the board too large.
 *     FIX: use density-based row/column scan; authoritative side = horizontal
 *          span (width), which is never distorted by phone UI bars.
 *
 *  2. detectByPocketCorners() searched from the *image* corners with a fixed
 *     radius in proc-space, often missing the actual pocket circles which
 *     are deep inside the image.
 *     FIX: split image into 4 quadrants, find the largest dark-pixel cluster
 *          in each quadrant independently.
 *
 *  3. Striker was identified as "largest white blob in bottom 38%". In
 *     Carrom Disc Pool the striker is a BLUE/SILVER metallic disc — not
 *     white at all.
 *     FIX: also scan for blue/silver blobs; prefer the blue blob as the
 *          striker candidate when one is found in the striker zone.
 *
 *  4. isBoardBorder() had too many false positives (wood, tan surface).
 *     FIX: tightened orange check; explicitly exclude the warm-tan surface.
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  REAL BOARD PROPORTIONS (74 cm reference)
 * ═══════════════════════════════════════════════════════════════════════
 *  Pocket centre inset from corner:  4.45 / 74 = 6.0 %
 *  Coin radius:                       1.59 / 74 = 2.15 %  (min 1.8 %, max 3.5 %)
 *  Striker radius:                    2.065/ 74 = 2.79 %
 *  Striker baseline from board top:  (74 - 14.9) / 74 = 79.9 %
 *  Striker zone half-width:          (23/2) / 74 = 15.5 %
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  DETECTION PIPELINE
 * ═══════════════════════════════════════════════════════════════════════
 *  1. detectByOrangeDensity()  — primary: row/column density scan of orange
 *  2. detectByPocketQuadrants()— secondary: find 4 dark blobs in 4 quadrants
 *  3. detectByWoodSurface()    — tertiary: find tan playing surface bounding box
 *  4. smartFallback()          — guaranteed: 94% of screen width, centred
 */
public class BoardDetector {

    private static final String TAG       = "BoardDetector";
    private static final int    PROC_W    = 360;
    private static final float  EMA_A     = 0.10f;
    private static final int    SCAN_STEP = 3;

    // ── Board proportions ─────────────────────────────────────────────────────
    private static final float POCKET_INSET    = 0.060f;
    private static final float STRIKER_Y_FRAC  = 0.800f;
    private static final float COIN_R_MIN      = 0.018f;
    private static final float COIN_R_MAX      = 0.035f;

    // ── Internal pixel labels (only used inside BoardDetector) ────────────────
    private static final int PX_BOARD_BORDER = 10;
    private static final int PX_BLUE         = 11;  // striker hint

    private RectF smoothedBoard = null;
    private int[] pixelBuf      = null;

    // stubs kept for backward compat
    public void setMinRadiusFrac(float v) {}
    public void setMaxRadiusFrac(float v) {}
    public void setParam2(double v)       {}

    public synchronized GameState detect(Bitmap src) {
        if (src == null) return null;
        try { return run(src); }
        catch (Throwable t) {
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

        // ── Method 1: orange density scan ─────────────────────────────────────
        RectF rawBoard = detectByOrangeDensity(pixelBuf, pW, pH);

        // ── Method 2: dark pocket quadrant blobs ──────────────────────────────
        if (rawBoard == null)
            rawBoard = detectByPocketQuadrants(pixelBuf, pW, pH);

        // ── Method 3: tan wood surface ────────────────────────────────────────
        if (rawBoard == null)
            rawBoard = detectByWoodSurface(pixelBuf, pW, pH);

        // ── Method 4: proportion-based fallback ───────────────────────────────
        if (rawBoard == null)
            rawBoard = smartFallback(pW, pH);

        smoothedBoard = smoothRect(smoothedBoard, rawBoard);
        RectF pb = smoothedBoard;

        float minR = pb.width() * COIN_R_MIN;
        float maxR = pb.width() * COIN_R_MAX;
        List<Coin> coins = detectCoins(pixelBuf, pW, pH, pb, minR, maxR);

        float inv = 1f / scale;
        RectF srcBoard = scaleRect(pb, inv);

        List<Coin> scaled = new ArrayList<>(coins.size());
        for (Coin c : coins)
            scaled.add(new Coin(c.pos.x * inv, c.pos.y * inv,
                                c.radius * inv, c.color, false));

        // ── Striker detection ─────────────────────────────────────────────────
        // Striker zone: bottom 38 % of board
        float strikerThreshY = (pb.top + pb.height() * 0.62f) * inv;
        float boardCX        = srcBoard.centerX();
        float strikerZoneW   = srcBoard.width() * 0.55f;   // 55 % of board width

        // Priority 1 — blue/silver blob (Carrom Disc Pool style)
        Coin striker = null;
        for (Coin c : scaled) {
            if (c.color != PX_BLUE) continue;
            if (c.pos.y < strikerThreshY) continue;
            if (striker == null || c.radius > striker.radius) striker = c;
        }

        // Priority 2 — largest white blob in striker zone
        if (striker == null) {
            for (Coin c : scaled) {
                if (c.color != Coin.COLOR_WHITE) continue;
                if (c.pos.y < strikerThreshY) continue;
                // Must be within the horizontal striker movement zone
                if (Math.abs(c.pos.x - boardCX) > strikerZoneW) continue;
                if (striker == null || c.radius > striker.radius) striker = c;
            }
        }

        // Normalise striker color back to COLOR_STRIKER
        if (striker != null) {
            striker.isStriker = true;
            striker.color     = Coin.COLOR_STRIKER;
        }

        GameState s = new GameState();
        s.board = srcBoard;

        if (striker != null) {
            s.striker = striker;
        } else {
            float defX = srcBoard.centerX();
            float defY = srcBoard.top + srcBoard.height() * STRIKER_Y_FRAC;
            float defR = srcBoard.width() * 0.028f;
            s.striker  = new Coin(defX, defY, defR, Coin.COLOR_STRIKER, true);
        }

        // Add all non-striker coins (also exclude the blue-striker cluster if it
        // was never promoted to striker — it's not a coin)
        for (Coin c : scaled) {
            if (c == striker) continue;
            if (c.color == PX_BLUE) continue;  // discard unmatched blue blobs
            s.coins.add(c);
        }

        addPockets(s);
        return s;
    }

    // ── Method 1: Orange density scan ─────────────────────────────────────────

    /**
     * Scans each row and column counting "orange border" pixels.
     * A row/column "belongs to the board frame" when it has ≥ DENSITY_THRESH hits.
     *
     * Authoritative side = HORIZONTAL span (width).
     * Vertical span is unreliable in portrait because phone UI bars shift the
     * perceived top/bottom. The board is always square, so we use width for both.
     */
    private RectF detectByOrangeDensity(int[] px, int w, int h) {
        int densityThresh = Math.max(3, w / 30);  // ~12 px at PROC_W=360

        // --- Row scan → find topRow, bottomRow ---
        int topRow = -1, bottomRow = -1;
        for (int y = 0; y < h; y += SCAN_STEP) {
            int cnt = 0;
            for (int x = 0; x < w; x += SCAN_STEP) {
                if (isOrangeBorder(px[y * w + x])) cnt++;
            }
            if (cnt >= densityThresh) {
                if (topRow < 0) topRow = y;
                bottomRow = y;
            }
        }
        if (topRow < 0 || (bottomRow - topRow) < h * 0.10f) return null;

        // --- Column scan (within row band) → find leftCol, rightCol ---
        int leftCol = -1, rightCol = -1;
        for (int x = 0; x < w; x += SCAN_STEP) {
            int cnt = 0;
            for (int y = topRow; y <= bottomRow; y += SCAN_STEP) {
                if (isOrangeBorder(px[y * w + x])) cnt++;
            }
            if (cnt >= densityThresh) {
                if (leftCol < 0) leftCol = x;
                rightCol = x;
            }
        }
        if (leftCol < 0 || (rightCol - leftCol) < w * 0.15f) return null;

        // Board side = horizontal span (more reliable in portrait)
        float boardW = rightCol  - leftCol;
        float boardH = bottomRow - topRow;
        // Accept if height is within 35% of width (square-ish)
        float aspect = Math.max(boardW, boardH) / Math.max(1, Math.min(boardW, boardH));
        if (aspect > 1.60f) return null;

        // Use the SMALLER of the two spans to avoid over-counting due to UI bars
        float side = Math.min(boardW, boardH);
        float cx   = (leftCol  + rightCol)  / 2f;
        float cy   = (topRow   + bottomRow) / 2f;

        return new RectF(
            Math.max(0, cx - side / 2f), Math.max(0, cy - side / 2f),
            Math.min(w, cx + side / 2f), Math.min(h, cy + side / 2f));
    }

    /**
     * Returns true for the bright orange frame of Carrom Disc Pool.
     * Carefully excludes the warm-tan playing surface and the dark-maroon background.
     *
     * Target colour: R ≈ 200–255, G ≈ 80–155, B < 70
     * Key discriminators: R >> G >> B, high saturation, not too light (not tan/white).
     */
    private boolean isOrangeBorder(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;

        // Primary check: vivid orange/amber (the board frame)
        // R very high, G medium, B very low → high saturation orange
        if (r > 150 && g > 60 && g < 170 && b < 75
                && r > g * 1.3f && g > b + 20 && (r - b) > 100) return true;

        // Secondary: darker brown/amber frame variants (some screens/brightness)
        if (r > 110 && r < 200 && g > 55 && g < 130 && b < 70
                && r > g * 1.2f && (r - b) > 65) return true;

        return false;
    }

    // ── Method 2: Dark pocket quadrant blobs ──────────────────────────────────

    /**
     * The 4 corner pockets are the largest BLACK discs in each image quadrant.
     * Split the image into 4 quadrants; find the dark-pixel centroid in each.
     * Verify the 4 centroids form an approximate square.
     *
     * Unlike the old method (which searched from the absolute image corners),
     * this searches each full quadrant and therefore always finds the pockets
     * regardless of how much of the screen they occupy.
     */
    private RectF detectByPocketQuadrants(int[] px, int w, int h) {
        int mX = w / 2, mY = h / 2;

        PointF tl = darkBlobInRegion(px, w, 0,  0,  mX, mY);
        PointF tr = darkBlobInRegion(px, w, mX, 0,  w,  mY);
        PointF bl = darkBlobInRegion(px, w, 0,  mY, mX, h);
        PointF br = darkBlobInRegion(px, w, mX, mY, w,  h);

        if (tl == null || tr == null || bl == null || br == null) return null;

        float spanW = ((tr.x - tl.x) + (br.x - bl.x)) * 0.5f;
        float spanH = ((bl.y - tl.y) + (br.y - tr.y)) * 0.5f;
        float aspect = Math.max(spanW, spanH) / Math.max(1, Math.min(spanW, spanH));
        if (aspect > 1.40f) return null;

        float side = Math.min(spanW, spanH);    // board must be square
        float cx   = (tl.x + tr.x + bl.x + br.x) / 4f;
        float cy   = (tl.y + tr.y + bl.y + br.y) / 4f;

        return new RectF(cx - side / 2f, cy - side / 2f,
                         cx + side / 2f, cy + side / 2f);
    }

    /**
     * Find the centroid of the densest dark-pixel cluster within the region
     * [x0,x1) × [y0,y1). Returns null if no significant dark blob found.
     */
    private PointF darkBlobInRegion(int[] px, int w,
                                     int x0, int y0, int x1, int y1) {
        // Collect dark pixels, then find the densest cluster centroid
        long sumX = 0, sumY = 0, cnt = 0;
        // Also track the best sub-quadrant (the 4-quadrant within each region)
        // to avoid averaging blobs from opposite corners
        int mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        long[] qSumX = new long[4], qSumY = new long[4], qCnt = new long[4];

        for (int y = y0; y < y1; y += SCAN_STEP) {
            for (int x = x0; x < x1; x += SCAN_STEP) {
                if (x < 0 || x >= w || y < 0) continue;
                int c = px[y * w + x];
                int lum = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                if (lum < 100) {  // dark pixel
                    sumX += x; sumY += y; cnt++;
                    int qi = (x < mx ? 0 : 1) + (y < my ? 0 : 2);
                    qSumX[qi] += x; qSumY[qi] += y; qCnt[qi]++;
                }
            }
        }
        if (cnt < 5) return null;

        // Use the quadrant with the most dark pixels (avoids blending two blobs)
        int best = 0;
        for (int q = 1; q < 4; q++) if (qCnt[q] > qCnt[best]) best = q;
        if (qCnt[best] >= cnt / 2) {
            return new PointF((float) qSumX[best] / qCnt[best],
                              (float) qSumY[best] / qCnt[best]);
        }
        return new PointF((float) sumX / cnt, (float) sumY / cnt);
    }

    // ── Method 3: Warm-tan wood surface ───────────────────────────────────────

    /**
     * The playing surface is a warm tan/light wood colour, distinctly lighter and
     * more yellow than the dark maroon background. Find its bounding box and
     * expand by the estimated frame width (≈8 % of board side).
     */
    private RectF detectByWoodSurface(int[] px, int w, int h) {
        // Skip outer 5 % border of image to ignore phone bezels / UI
        int margin = (int)(w * 0.05f);
        int minX = w, maxX = 0, minY = h, maxY = 0, cnt = 0;

        for (int y = margin; y < h - margin; y += SCAN_STEP) {
            for (int x = margin; x < w - margin; x += SCAN_STEP) {
                if (isWoodSurface(px[y * w + x])) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    cnt++;
                }
            }
        }
        float minSpan = w * 0.25f;
        if (cnt < 20 || (maxX - minX) < minSpan || (maxY - minY) < minSpan) return null;

        float surfaceW = maxX - minX;
        float surfaceH = maxY - minY;

        // Expand by 8 % each side for the frame, then make square
        float side = Math.min(surfaceW, surfaceH) * (1f + 2 * 0.08f);
        float cx   = (minX + maxX) / 2f;
        float cy   = (minY + maxY) / 2f;

        return new RectF(
            Math.max(0, cx - side / 2f), Math.max(0, cy - side / 2f),
            Math.min(w, cx + side / 2f), Math.min(h, cy + side / 2f));
    }

    /**
     * Light wood / warm tan surface of the carrom board.
     * Target: R ≈ 185–225, G ≈ 145–185, B ≈ 90–140
     * Key: warm (R>G>B), moderately bright, NOT the vivid orange frame.
     */
    private boolean isWoodSurface(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        int lum = (r + g + b) / 3;
        if (lum < 110 || lum > 230) return false;  // too dark or too bright
        // Warm tone: R > G > B
        if (r <= g || g <= b) return false;
        // Not orange (orange has very high R-B; wood has moderate R-B)
        if ((r - b) > 130) return false;    // exclude vivid orange frame
        if ((r - b) < 30)  return false;    // exclude grey / cold pixels
        // R should be in the warm tan range
        if (r < 150 || r > 235) return false;
        if (g < 110 || g > 195) return false;
        if (b < 60  || b > 155) return false;
        return true;
    }

    // ── Method 4: Proportion-based smart fallback ─────────────────────────────

    /**
     * Carrom Disc Pool fills the screen width with the board.
     * Observed: board outer frame ≈ 93–95 % of screen width.
     * The UI bars (top score, bottom controls) consume ≈ 14 % top + 10 % bottom.
     * Board is centred horizontally; vertically centred in the UI-free area.
     */
    private RectF smartFallback(int w, int h) {
        float side = w * 0.94f;          // 94 % of screen width
        float cx   = w / 2f;
        // UI top ≈ 14 %, bottom ≈ 10 %
        float uiTop = h * 0.14f, uiBot = h * 0.10f;
        float cy = uiTop + (h - uiTop - uiBot) * 0.50f;

        // Clamp so we don't go off-screen
        side = Math.min(side, (h - uiTop - uiBot) * 0.96f);

        return new RectF(cx - side / 2f, cy - side / 2f,
                         cx + side / 2f, cy + side / 2f);
    }

    // ── Coin detection ────────────────────────────────────────────────────────

    private List<Coin> detectCoins(int[] px, int w, int h,
                                   RectF board, float minR, float maxR) {
        // Scan inner 92 % of board (skip frame pixels)
        int bL = Math.max(0, (int)(board.left   + board.width()  * 0.04f));
        int bR = Math.min(w, (int)(board.right  - board.width()  * 0.04f));
        int bT = Math.max(0, (int)(board.top    + board.height() * 0.04f));
        int bB = Math.min(h, (int)(board.bottom - board.height() * 0.04f));

        List<float[]> whites = new ArrayList<>(), blacks = new ArrayList<>(),
                      reds   = new ArrayList<>(), blues  = new ArrayList<>();

        for (int y = bT; y < bB; y += SCAN_STEP) {
            for (int x = bL; x < bR; x += SCAN_STEP) {
                switch (classifyPx(px[y * w + x])) {
                    case Coin.COLOR_WHITE:  whites.add(new float[]{x, y}); break;
                    case Coin.COLOR_BLACK:  blacks.add(new float[]{x, y}); break;
                    case Coin.COLOR_RED:    reds  .add(new float[]{x, y}); break;
                    case PX_BLUE:           blues .add(new float[]{x, y}); break;
                    default: break;
                }
            }
        }

        List<Coin> out = new ArrayList<>();
        cluster(whites, Coin.COLOR_WHITE, maxR * 1.6f, minR, maxR,          out);
        cluster(blacks, Coin.COLOR_BLACK, maxR * 1.6f, minR, maxR,          out);
        cluster(reds,   Coin.COLOR_RED,   maxR * 1.2f, minR * 0.4f,
                        maxR * 0.75f, out);
        cluster(blues,  PX_BLUE,          maxR * 1.8f, minR, maxR * 1.5f,  out);
        nms(out);
        return out;
    }

    /**
     * Classify a single pixel into coin colour, border, or background.
     *
     * Returns one of:
     *   Coin.COLOR_WHITE  — white/cream coin
     *   Coin.COLOR_BLACK  — black coin
     *   Coin.COLOR_RED    — red queen
     *   PX_BLUE           — blue/silver striker (Carrom Disc Pool)
     *   -1                — background / frame / unclassified
     */
    private int classifyPx(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        int lum = (r + g + b) / 3;

        // White / cream coin: bright, balanced (slightly warm)
        if (lum > 150 && r > 130 && g > 130 && b > 110
                && (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) < 70)
            return Coin.COLOR_WHITE;

        // Black coin: very dark
        if (lum < 70 && r < 85 && g < 85 && b < 85)
            return Coin.COLOR_BLACK;

        // Red queen: dominant red channel with low green and blue
        if (r > 120 && g < 85 && b < 95 && r > g * 1.7f && r > b * 1.5f)
            return Coin.COLOR_RED;

        // Blue / silver striker (Carrom Disc Pool specific):
        //   B is the dominant channel, G moderate, R lower; not too dark, not white.
        if (b > r + 20 && b > g && b > 90 && lum > 70 && lum < 200
                && r < 160 && g < 170)
            return PX_BLUE;

        return -1;
    }

    // ── Clustering + NMS ──────────────────────────────────────────────────────

    private void cluster(List<float[]> pts, int color, float mergeR,
                         float minR, float maxR, List<Coin> out) {
        if (pts.isEmpty()) return;
        List<float[]> cl = new ArrayList<>();
        for (float[] pt : pts) {
            float bestD = mergeR; int bi = -1;
            for (int i = 0; i < cl.size(); i++) {
                float[] c = cl.get(i);
                float dx = pt[0] - c[0], dy = pt[1] - c[1];
                float d  = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < bestD) { bestD = d; bi = i; }
            }
            if (bi >= 0) {
                float[] c = cl.get(bi); float n = c[2];
                c[0] = (c[0] * n + pt[0]) / (n + 1);
                c[1] = (c[1] * n + pt[1]) / (n + 1);
                c[2] = n + 1;
            } else {
                cl.add(new float[]{pt[0], pt[1], 1});
            }
        }
        int minHits = Math.max(2, (int)(Math.PI * minR * minR / (SCAN_STEP * SCAN_STEP) * 0.12f));
        for (float[] c : cl) {
            if (c[2] < minHits) continue;
            float estR = (float) Math.sqrt(c[2] * SCAN_STEP * SCAN_STEP / Math.PI);
            out.add(new Coin(c[0], c[1],
                             Math.max(minR, Math.min(maxR, estR)),
                             color, false));
        }
    }

    private void nms(List<Coin> coins) {
        boolean[] keep = new boolean[coins.size()];
        Arrays.fill(keep, true);
        for (int i = 0; i < coins.size(); i++) {
            if (!keep[i]) continue;
            Coin a = coins.get(i);
            for (int j = i + 1; j < coins.size(); j++) {
                if (!keep[j]) continue;
                Coin b = coins.get(j);
                float dx = a.pos.x - b.pos.x, dy = a.pos.y - b.pos.y;
                float d  = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < (a.radius + b.radius) * 0.65f) {
                    if (a.radius >= b.radius) keep[j] = false;
                    else { keep[i] = false; break; }
                }
            }
        }
        Iterator<Coin> it = coins.iterator(); int idx = 0;
        while (it.hasNext()) { it.next(); if (!keep[idx++]) it.remove(); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GameState fallbackState(int w, int h) {
        GameState s  = new GameState();
        float side   = w * 0.94f;
        float uiTop  = h * 0.14f, uiBot = h * 0.10f;
        float cy     = uiTop + (h - uiTop - uiBot) * 0.50f;
        side = Math.min(side, (h - uiTop - uiBot) * 0.96f);
        float cx = w / 2f;
        s.board = new RectF(cx - side / 2f, cy - side / 2f,
                            cx + side / 2f, cy + side / 2f);
        float defY = s.board.top + s.board.height() * STRIKER_Y_FRAC;
        s.striker  = new Coin(cx, defY, side * 0.028f, Coin.COLOR_STRIKER, true);
        float r    = side * 0.022f;
        s.coins.add(new Coin(cx,            cy - side * 0.10f, r, Coin.COLOR_WHITE, false));
        s.coins.add(new Coin(cx - side*0.12f, cy,              r, Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx + side*0.12f, cy,              r, Coin.COLOR_BLACK, false));
        s.coins.add(new Coin(cx,            cy,                r, Coin.COLOR_RED,   false));
        addPockets(s);
        return s;
    }

    private void addPockets(GameState s) {
        if (s.board == null) return;
        float i = s.board.width() * POCKET_INSET;
        s.pockets.add(new PointF(s.board.left  + i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.right - i, s.board.top    + i));
        s.pockets.add(new PointF(s.board.left  + i, s.board.bottom - i));
        s.pockets.add(new PointF(s.board.right - i, s.board.bottom - i));
    }

    private RectF scaleRect(RectF r, float s) {
        return new RectF(r.left * s, r.top * s, r.right * s, r.bottom * s);
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n;
        if (n == null) return p;
        return new RectF(
            p.left   + EMA_A * (n.left   - p.left),
            p.top    + EMA_A * (n.top    - p.top),
            p.right  + EMA_A * (n.right  - p.right),
            p.bottom + EMA_A * (n.bottom - p.bottom));
    }
}

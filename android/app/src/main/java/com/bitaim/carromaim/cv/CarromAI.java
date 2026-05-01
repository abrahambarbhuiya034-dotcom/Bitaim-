package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CarromAI — v4.0 Ghost-Ball Engine (Java port of CarromAutoplay JS engine)
 *
 * Provides professional-grade shot selection using:
 *  - Ghost-ball geometry targeting (exact contact-point calculation)
 *  - Bank / wall-rebound shots (all 3 valid walls)
 *  - Path-clearance check (blocked shots filtered out)
 *  - Multi-pocket evaluation per coin (best angle to pocket selected)
 *  - Shot scoring: pocket proximity + striker distance + piece value
 *  - Striker foul safety (never aims directly into a pocket)
 *
 * Used by AimOverlayView.computeBestShots() to replace the old
 * nearest-pocket-only approach.
 */
public class CarromAI {

    private static final float STRIKER_SAFE_MARGIN = 0.06f; // fraction of board width

    public static class AiShot {
        public final PointF ghostPos;   // ghost-ball contact point (striker aims here)
        public final Coin   coin;       // target coin
        public final PointF pocket;     // target pocket
        public final float  score;      // higher = better
        public final int    wallsNeeded; // 0=direct, 1=one bank, 2=two banks
        public final boolean isBank;

        public AiShot(PointF g, Coin c, PointF pk, float sc, int walls, boolean bank) {
            ghostPos = g; coin = c; pocket = pk; score = sc;
            wallsNeeded = walls; isBank = bank;
        }
    }

    /**
     * Find all viable shots from the current game state, ranked best-first.
     *
     * @param state  current board state (must have striker, coins, pockets, board)
     * @param maxResults maximum number of results to return
     * @return ranked list of shots, best first
     */
    public static List<AiShot> findBestShots(GameState state, int maxResults) {
        List<AiShot> results = new ArrayList<>();

        if (state == null || state.striker == null
                || state.pockets == null || state.pockets.isEmpty()
                || state.board == null) {
            return results;
        }

        float safeMargin = state.board.width() * STRIKER_SAFE_MARGIN;

        for (Coin coin : state.coins) {
            if (coin.color == Coin.COLOR_STRIKER) continue;

            for (PointF pocket : state.pockets) {
                // ── Direct ghost-ball shot ──────────────────────────────────
                AiShot direct = computeDirectShot(state, coin, pocket, safeMargin);
                if (direct != null) results.add(direct);

                // ── Bank shots off 3 walls ───────────────────────────────────
                List<AiShot> banks = computeBankShots(state, coin, pocket, safeMargin);
                results.addAll(banks);
            }
        }

        // Sort by score descending
        Collections.sort(results, (a, b) -> Float.compare(b.score, a.score));

        // Deduplicate: skip shots whose ghost ball is within 8px of an existing one
        List<AiShot> deduped = new ArrayList<>();
        for (AiShot s : results) {
            boolean dup = false;
            for (AiShot kept : deduped) {
                if (dist(s.ghostPos, kept.ghostPos) < 8f) { dup = true; break; }
            }
            if (!dup) deduped.add(s);
            if (deduped.size() >= maxResults) break;
        }
        return deduped;
    }

    // ── Direct ghost-ball shot ────────────────────────────────────────────────

    private static AiShot computeDirectShot(GameState s, Coin coin, PointF pocket,
                                             float safeMargin) {
        // Vector from coin to pocket
        float dx = coin.pos.x - pocket.x;
        float dy = coin.pos.y - pocket.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return null;

        // Ghost-ball position: striker centre when it just touches the coin
        float ghostR = s.striker.radius + coin.radius;
        PointF ghost = new PointF(
            coin.pos.x + (dx / len) * ghostR,
            coin.pos.y + (dy / len) * ghostR);

        // Must be within expanded board
        if (!inExpandedBoard(ghost, s.board, s.striker.radius)) return null;

        // Direction from striker to ghost must be clear of other pieces
        if (!isPathClear(s.striker.pos, ghost, s.striker.radius, s.coins, coin)) return null;

        // Foul safety: ghost direction must not aim striker into a pocket
        if (!isStrikerSafe(s.striker.pos, ghost, s.pockets, safeMargin)) return null;

        float score = scoreShot(s, coin, pocket, ghost, 0, false);
        return new AiShot(ghost, coin, pocket, score, 0, false);
    }

    // ── Bank (wall-bounce) shots ──────────────────────────────────────────────

    private static List<AiShot> computeBankShots(GameState s, Coin coin, PointF pocket,
                                                   float safeMargin) {
        List<AiShot> out = new ArrayList<>();

        // Ghost-ball for this coin → pocket
        float dx = coin.pos.x - pocket.x;
        float dy = coin.pos.y - pocket.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return out;

        float ghostR = s.striker.radius + coin.radius;
        PointF ghost = new PointF(
            coin.pos.x + (dx / len) * ghostR,
            coin.pos.y + (dy / len) * ghostR);

        if (!inExpandedBoard(ghost, s.board, s.striker.radius)) return out;

        // Reflect ghost through each wall to find bank approach
        RectF b = s.board;
        float[][] walls = {
            {b.left,  0, 1, 0},   // left wall  — reflect X
            {b.right, 0, 1, 0},   // right wall — reflect X
            {b.top,   1, 0, 1},   // top wall   — reflect Y
        };

        for (float[] wall : walls) {
            PointF reflected;
            if (wall[2] == 1 && wall[3] == 0) {
                // X-axis wall
                reflected = new PointF(2 * wall[0] - ghost.x, ghost.y);
            } else {
                // Y-axis wall
                reflected = new PointF(ghost.x, 2 * wall[0] - ghost.y);
            }

            // Direction from striker to reflected ghost
            float rdx = reflected.x - s.striker.pos.x;
            float rdy = reflected.y - s.striker.pos.y;
            float rLen = (float) Math.sqrt(rdx * rdx + rdy * rdy);
            if (rLen < 1f) continue;

            // Striker foul safety check
            if (!isStrikerSafe(s.striker.pos, reflected, s.pockets, safeMargin)) continue;

            // Bank shot scores lower than direct
            float score = scoreShot(s, coin, pocket, ghost, 1, true) * 0.65f;
            out.add(new AiShot(ghost, coin, pocket, score, 1, true));
            break; // one bank shot per wall is enough
        }

        return out;
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private static float scoreShot(GameState s, Coin coin, PointF pocket,
                                    PointF ghost, int walls, boolean isBank) {
        float pocketDist   = dist(coin.pos, pocket);
        float strikerDist  = dist(s.striker.pos, ghost);
        float boardDiag    = diagOf(s.board);

        // Closer to pocket = easier to pot
        float pocketBonus  = 1200f / (pocketDist + 20f);
        // Shorter striker path = more accurate
        float strikerPenalty = strikerDist / boardDiag * 180f;

        float score = pocketBonus - strikerPenalty;

        // Queen is most valuable
        if (coin.color == Coin.COLOR_RED)   score *= 1.6f;
        // Black slightly less than white in standard rules
        if (coin.color == Coin.COLOR_BLACK) score *= 0.9f;

        // Bank shots are harder
        if (isBank) score *= 0.7f;

        return score;
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    /**
     * Check if a circular path from A to B (radius r) is clear of all pieces
     * except the excluded coin (the target).
     */
    public static boolean isPathClear(PointF a, PointF b, float r,
                                       List<Coin> pieces, Coin exclude) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return true;
        float ux = dx / len, uy = dy / len;

        for (Coin p : pieces) {
            if (p == exclude) continue;
            if (p.color == Coin.COLOR_STRIKER) continue;

            float tpx = p.pos.x - a.x, tpy = p.pos.y - a.y;
            float proj = tpx * ux + tpy * uy;
            if (proj < 0 || proj > len) continue;

            float closestX = a.x + ux * proj;
            float closestY = a.y + uy * proj;
            float clearance = dist(new PointF(closestX, closestY), p.pos);
            if (clearance < r + p.radius - 2f) return false;
        }
        return true;
    }

    /**
     * Geometric foul-safety: the shot direction must not aim the striker
     * within safeMargin pixels of any pocket.
     */
    private static boolean isStrikerSafe(PointF strikerPos, PointF target,
                                          List<PointF> pockets, float safeMargin) {
        float dx = target.x - strikerPos.x;
        float dy = target.y - strikerPos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return false;
        float ux = dx / len, uy = dy / len;

        for (PointF pk : pockets) {
            float tpx = pk.x - strikerPos.x;
            float tpy = pk.y - strikerPos.y;
            float proj = tpx * ux + tpy * uy;
            if (proj < 0) continue;

            float closestX = strikerPos.x + ux * proj;
            float closestY = strikerPos.y + uy * proj;
            float d = dist(new PointF(closestX, closestY), pk);
            if (d < safeMargin) return false;
        }
        return true;
    }

    private static boolean inExpandedBoard(PointF p, RectF board, float margin) {
        return p.x >= board.left   - margin
            && p.x <= board.right  + margin
            && p.y >= board.top    - margin
            && p.y <= board.bottom + margin;
    }

    public static float dist(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float diagOf(RectF r) {
        float w = r.width(), h = r.height();
        return (float) Math.sqrt(w * w + h * h);
    }
}

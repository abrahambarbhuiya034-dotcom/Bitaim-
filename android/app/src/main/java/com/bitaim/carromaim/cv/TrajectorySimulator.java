package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * TrajectorySimulator
 *
 * Step-based 2D physics simulation for carrom pieces.
 * Predicts the path of the striker AND every coin it touches (including
 * coin-on-coin chain reactions and cushion bounces) up to a fixed time horizon.
 *
 * Output is a list of PathSegments, one per moving body, that the overlay
 * view renders as polylines in distinct colors.
 */
public class TrajectorySimulator {

    /** Simulation timestep in seconds. Smaller = more accurate, slower. */
    private static final float DT = 1f / 120f;
    /** Max simulation horizon in seconds. */
    private static final float MAX_TIME = 4f;
    /** Linear friction (per second). 1.0 = no friction. */
    private static final float FRICTION = 0.65f;
    /** Initial striker speed (px / sec). Scaled by sensitivity. */
    private static final float STRIKER_BASE_SPEED = 4500f;
    /** Velocity below which a body stops. */
    private static final float STOP_SPEED = 25f;
    /** Maximum number of wall + coin contact events recorded per body. */
    private static final int MAX_EVENTS = 12;

    public static class PathSegment {
        public List<PointF> points = new ArrayList<>();
        public int kind; // 0 = striker, 1 = coin (white), 2 = coin (black), 3 = queen
        public boolean enteredPocket = false;
        public int wallBounces = 0;
    }

    private static class Body {
        PointF pos;
        PointF vel;
        float radius;
        float mass;
        int kind;
        boolean active = true;
        boolean potted = false;
        PathSegment path = new PathSegment();
        int wallBounces = 0;
        int coinHits = 0;
    }

    /**
     * Simulate the full shot.
     *
     * @param striker   detected striker (position + radius)
     * @param target    aim target point on screen (where user touched)
     * @param coins     detected coins
     * @param pockets   pocket centers
     * @param board     board rectangle (cushion bounds)
     * @param sensitivity speed multiplier (0.3..3.0)
     */
    public List<PathSegment> simulate(
            Coin striker, PointF target,
            List<Coin> coins, List<PointF> pockets,
            RectF board, float sensitivity
    ) {
        List<Body> bodies = new ArrayList<>();
        if (striker == null || target == null || board == null) return new ArrayList<>();

        // Striker — initial velocity toward target
        float dx = target.x - striker.pos.x;
        float dy = target.y - striker.pos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return new ArrayList<>();
        float speed = STRIKER_BASE_SPEED * Math.max(0.3f, Math.min(sensitivity, 3.0f));

        Body s = new Body();
        s.pos = new PointF(striker.pos.x, striker.pos.y);
        s.vel = new PointF(dx / len * speed, dy / len * speed);
        s.radius = striker.radius;
        s.mass = 1.2f;
        s.kind = 0;
        s.path.kind = 0;
        s.path.points.add(new PointF(s.pos.x, s.pos.y));
        bodies.add(s);

        // Coins — stationary
        if (coins != null) {
            for (Coin c : coins) {
                Body b = new Body();
                b.pos = new PointF(c.pos.x, c.pos.y);
                b.vel = new PointF(0, 0);
                b.radius = c.radius;
                b.mass = 1f;
                b.kind = (c.color == Coin.COLOR_BLACK) ? 2
                        : (c.color == Coin.COLOR_RED) ? 3 : 1;
                b.path.kind = b.kind;
                b.path.points.add(new PointF(b.pos.x, b.pos.y));
                bodies.add(b);
            }
        }

        // Step loop
        float t = 0;
        float pocketRadius = (board.width() * 0.04f);

        while (t < MAX_TIME) {
            t += DT;
            boolean anyMoving = false;

            for (Body b : bodies) {
                if (!b.active) continue;
                if (speedOf(b.vel) < STOP_SPEED && b.kind == 0 && b.coinHits == 0 && b.wallBounces == 0) {
                    // striker hasn't done anything — keep going
                } else if (speedOf(b.vel) < STOP_SPEED) {
                    b.vel.set(0, 0);
                    if (b.kind == 0) {
                        // striker stopped — no point continuing
                    }
                    continue;
                }
                anyMoving = true;

                // Integrate
                b.pos.x += b.vel.x * DT;
                b.pos.y += b.vel.y * DT;

                // Friction
                float decay = (float) Math.pow(FRICTION, DT);
                b.vel.x *= decay;
                b.vel.y *= decay;

                // Wall bounces
                if (b.pos.x - b.radius < board.left) {
                    b.pos.x = board.left + b.radius;
                    b.vel.x = -b.vel.x * 0.92f;
                    b.wallBounces++;
                    if (b.wallBounces > MAX_EVENTS) b.active = false;
                    b.path.points.add(new PointF(b.pos.x, b.pos.y));
                } else if (b.pos.x + b.radius > board.right) {
                    b.pos.x = board.right - b.radius;
                    b.vel.x = -b.vel.x * 0.92f;
                    b.wallBounces++;
                    if (b.wallBounces > MAX_EVENTS) b.active = false;
                    b.path.points.add(new PointF(b.pos.x, b.pos.y));
                }
                if (b.pos.y - b.radius < board.top) {
                    b.pos.y = board.top + b.radius;
                    b.vel.y = -b.vel.y * 0.92f;
                    b.wallBounces++;
                    if (b.wallBounces > MAX_EVENTS) b.active = false;
                    b.path.points.add(new PointF(b.pos.x, b.pos.y));
                } else if (b.pos.y + b.radius > board.bottom) {
                    b.pos.y = board.bottom - b.radius;
                    b.vel.y = -b.vel.y * 0.92f;
                    b.wallBounces++;
                    if (b.wallBounces > MAX_EVENTS) b.active = false;
                    b.path.points.add(new PointF(b.pos.x, b.pos.y));
                }

                // Pocket capture
                for (PointF p : pockets) {
                    float pd = dist(b.pos.x, b.pos.y, p.x, p.y);
                    if (pd < pocketRadius) {
                        b.potted = true;
                        b.active = false;
                        b.path.enteredPocket = true;
                        b.path.points.add(new PointF(p.x, p.y));
                        break;
                    }
                }
            }

            // Pairwise collision resolution (elastic)
            int n = bodies.size();
            for (int i = 0; i < n; i++) {
                Body a = bodies.get(i);
                if (!a.active) continue;
                for (int j = i + 1; j < n; j++) {
                    Body b = bodies.get(j);
                    if (!b.active) continue;
                    float d = dist(a.pos.x, a.pos.y, b.pos.x, b.pos.y);
                    float r = a.radius + b.radius;
                    if (d < r && d > 0.001f) {
                        resolveCollision(a, b, d);
                        a.coinHits++;
                        b.coinHits++;
                        a.path.points.add(new PointF(a.pos.x, a.pos.y));
                        b.path.points.add(new PointF(b.pos.x, b.pos.y));
                    }
                }
            }

            // Sample a path point every few steps to keep the polyline lean
            if ((int) (t / DT) % 4 == 0) {
                for (Body b : bodies) {
                    if (!b.active) continue;
                    if (speedOf(b.vel) < STOP_SPEED) continue;
                    PointF last = b.path.points.get(b.path.points.size() - 1);
                    if (dist(last.x, last.y, b.pos.x, b.pos.y) > 6) {
                        b.path.points.add(new PointF(b.pos.x, b.pos.y));
                    }
                }
            }

            if (!anyMoving) break;
        }

        // Build output: only paths that actually moved
        List<PathSegment> out = new ArrayList<>();
        for (Body b : bodies) {
            if (b.path.points.size() < 2) continue;
            b.path.wallBounces = b.wallBounces;
            // Add final position
            b.path.points.add(new PointF(b.pos.x, b.pos.y));
            out.add(b.path);
        }
        return out;
    }

    private static void resolveCollision(Body a, Body b, float d) {
        // Normal vector from a to b
        float nx = (b.pos.x - a.pos.x) / d;
        float ny = (b.pos.y - a.pos.y) / d;

        // Push apart so they're exactly touching
        float overlap = (a.radius + b.radius) - d;
        a.pos.x -= nx * overlap * 0.5f;
        a.pos.y -= ny * overlap * 0.5f;
        b.pos.x += nx * overlap * 0.5f;
        b.pos.y += ny * overlap * 0.5f;

        // Relative velocity along normal
        float rvx = b.vel.x - a.vel.x;
        float rvy = b.vel.y - a.vel.y;
        float vn = rvx * nx + rvy * ny;
        if (vn > 0) return; // already separating

        float restitution = 0.94f;
        float jImp = -(1 + restitution) * vn / (1f / a.mass + 1f / b.mass);
        float ix = jImp * nx;
        float iy = jImp * ny;
        a.vel.x -= ix / a.mass;
        a.vel.y -= iy / a.mass;
        b.vel.x += ix / b.mass;
        b.vel.y += iy / b.mass;
    }

    private static float speedOf(PointF v) {
        return (float) Math.sqrt(v.x * v.x + v.y * v.y);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}

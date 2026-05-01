package com.bitaim.carromaim;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — AccessibilityService that injects swipe gestures to shoot.
 *
 * How Carrom Disc Pool works:
 *   1. A horizontal slider at the bottom positions the striker X.
 *   2. The player swipes the striker icon on the board toward the target.
 *
 * AutoPlay fires two sequential strokes:
 *   Stroke 1 (0 ms)   — slide the bottom slider to the target X position.
 *   Stroke 2 (350 ms) — swipe from the striker toward the target coin/pocket.
 *
 * Enable in: Android Settings → Accessibility → AIMxASSIST.
 */
public class AutoShootService extends AccessibilityService {

    public static volatile AutoShootService INSTANCE;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        INSTANCE = null;
        super.onDestroy();
    }

    /** @return true if the service is connected and gestures can be dispatched. */
    public boolean isConnected() {
        return INSTANCE != null;
    }

    /**
     * Perform a two-stroke shot gesture.
     *
     * @param sliderY        Y coordinate of the bottom slider bar (screen pixels)
     * @param currentSliderX Current X of the slider indicator
     * @param targetSliderX  Target X to move the slider to (positions striker)
     * @param strikerX       Striker X on the board
     * @param strikerY       Striker Y on the board
     * @param aimX           X of the ghost-ball / target point
     * @param aimY           Y of the ghost-ball / target point
     * @param power          Shot power 0..1 (controls swipe distance)
     */
    public boolean performShot(
            float sliderY,
            float currentSliderX, float targetSliderX,
            float strikerX, float strikerY,
            float aimX, float aimY,
            float power) {
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();

            // Stroke 1: position the slider
            if (Math.abs(targetSliderX - currentSliderX) > 8f) {
                Path sliderPath = new Path();
                sliderPath.moveTo(currentSliderX, sliderY);
                sliderPath.lineTo(targetSliderX,  sliderY);
                builder.addStroke(new GestureDescription.StrokeDescription(
                        sliderPath, 0, 200, true));
            }

            // Stroke 2: swipe from striker toward the target (shoot)
            float dx = aimX - strikerX;
            float dy = aimY - strikerY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 1f) return false;

            float swipeDist = 70f + power * 130f; // 70–200 px based on power
            float endX = strikerX + (dx / dist) * swipeDist;
            float endY = strikerY + (dy / dist) * swipeDist;

            Path shotPath = new Path();
            shotPath.moveTo(strikerX, strikerY);
            shotPath.lineTo(endX, endY);
            builder.addStroke(new GestureDescription.StrokeDescription(
                    shotPath, 350, 280));

            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Simpler single-stroke shoot gesture when slider is already positioned.
     */
    public boolean shootFromStriker(
            float strikerX, float strikerY,
            float aimX, float aimY,
            float power) {
        try {
            float dx = aimX - strikerX;
            float dy = aimY - strikerY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 1f) return false;

            float swipeDist = 70f + power * 130f;
            Path path = new Path();
            path.moveTo(strikerX, strikerY);
            path.lineTo(strikerX + (dx / dist) * swipeDist,
                        strikerY + (dy / dist) * swipeDist);

            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));
            return dispatchGesture(b.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }
}

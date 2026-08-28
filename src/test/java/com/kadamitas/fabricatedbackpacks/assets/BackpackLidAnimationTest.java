package com.kadamitas.fabricatedbackpacks.assets;

import com.kadamitas.fabricatedbackpacks.block.BackpackLidAnimation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackpackLidAnimationTest {
    @Test
    void beginningMidpointAndEndAreContinuousAndBounded() {
        BackpackLidAnimation animation = new BackpackLidAnimation();
        assertEquals(0F, animation.openness(1F));
        float previous = 0F;
        for (int tick = 1; tick <= BackpackLidAnimation.DURATION_TICKS; tick++) {
            animation.tick(true);
            assertEquals(previous, animation.openness(0F));
            float current = animation.openness(1F);
            assertTrue(current > previous && current <= 1F);
            float middle = animation.openness(.5F);
            assertTrue(middle > previous && middle < current);
            if (tick == BackpackLidAnimation.DURATION_TICKS / 2) assertEquals(.5F, current);
            previous = current;
        }
        assertEquals(1F, animation.openness(1F));
        animation.tick(true);
        assertEquals(1F, animation.openness(0F));
        assertEquals(1F, animation.openness(.5F));
    }

    @Test
    void reversalKeepsTheLastPoseAndClosesWithoutOvershoot() {
        BackpackLidAnimation animation = new BackpackLidAnimation();
        for (int tick = 0; tick < 3; tick++) animation.tick(true);
        float turning = animation.openness(1F);
        animation.tick(false);
        assertEquals(turning, animation.openness(0F));
        assertTrue(animation.openness(.5F) < turning);
        for (int tick = 0; tick < BackpackLidAnimation.DURATION_TICKS + 2; tick++) animation.tick(false);
        assertEquals(0F, animation.openness(0F));
        assertEquals(0F, animation.openness(1F));
        animation.tick(true);
        assertEquals(0F, animation.openness(0F));
        assertTrue(animation.openness(1F) > 0F);
    }

    @Test
    void invalidRenderFractionsCannotCreateNonFiniteGeometry() {
        BackpackLidAnimation animation = new BackpackLidAnimation();
        animation.tick(true);
        assertEquals(animation.openness(0F), animation.openness(-100F));
        assertEquals(animation.openness(1F), animation.openness(100F));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertEquals(animation.openness(0F), animation.openness(invalid));
    }
}

package com.kadamitas.fabricatedbackpacks.block;

/** Client-local lid motion. This value is neither persisted nor sent to the server. */
public final class BackpackLidAnimation {
    public static final int DURATION_TICKS = 8;
    private float previous;
    private float current;

    public void tick(boolean open) {
        previous = current;
        current = Math.clamp(current + (open ? 1F : -1F) / DURATION_TICKS, 0F, 1F);
    }

    public float openness(float partialTick) {
        float partial = Float.isFinite(partialTick) ? Math.clamp(partialTick, 0F, 1F) : 0F;
        float progress = previous + (current - previous) * partial;
        return progress * progress * (3F - 2F * progress);
    }
}

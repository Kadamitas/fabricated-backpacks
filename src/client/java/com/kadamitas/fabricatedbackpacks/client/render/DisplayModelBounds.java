package com.kadamitas.fabricatedbackpacks.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import java.util.Map;
import java.util.WeakHashMap;

/** Measures actual baked vertices after the same FIXED transform used by ItemRenderer. */
final class DisplayModelBounds {
    private static final Map<BakedModel, AABB> CACHE = new WeakHashMap<>();
    private DisplayModelBounds() {}
    static synchronized AABB of(BakedModel model) { return CACHE.computeIfAbsent(model, DisplayModelBounds::measure); }

    private static AABB measure(BakedModel model) {
        PoseStack poses = new PoseStack();
        model.getTransforms().getTransform(ItemDisplayContext.FIXED).apply(false, poses);
        poses.translate(-.5F, -.5F, -.5F);
        var matrix = poses.last().pose();
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY);
        RandomSource random = RandomSource.create(42);
        int vertices = 0;
        for (int side = -1; side < 6; side++) {
            random.setSeed(42);
            for (var quad : model.getQuads(null, side < 0 ? null : Direction.values()[side], random)) {
                int[] data = quad.getVertices();
                int stride = data.length / 4;
                for (int index = 0; index < 4; index++) {
                    Vector3f point = new Vector3f(Float.intBitsToFloat(data[index * stride]),
                            Float.intBitsToFloat(data[index * stride + 1]), Float.intBitsToFloat(data[index * stride + 2]));
                    matrix.transformPosition(point);
                    min.min(point); max.max(point); vertices++;
                }
            }
        }
        // Built-in/custom renderers may have no baked quads. Their native unit envelope
        // still respects the real item transform and is rendered as an actual item model.
        if (vertices == 0) for (int mask = 0; mask < 8; mask++) {
            Vector3f point = new Vector3f(mask & 1, mask >> 1 & 1, mask >> 2 & 1);
            matrix.transformPosition(point);
            min.min(point); max.max(point);
        }
        return new AABB(min.x, min.y, min.z, max.x, max.y, max.z);
    }
}

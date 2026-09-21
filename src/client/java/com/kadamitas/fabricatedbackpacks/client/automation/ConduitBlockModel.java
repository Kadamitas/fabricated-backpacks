package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitVisualState;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Immutable public snapshots key native chunk geometry; no per-frame conduit renderer. */
final class ConduitBlockModel implements BlockStateModel {
    private static final String[] ROLES = {"tube", "collar", "endpoint", "endpoint_insert", "endpoint_extract", "endpoint_both"};
    private final Map<String, Material.Baked> materials;
    private final Map<ConduitVisualState, BlockStateModelPart> meshes = new LinkedHashMap<>(64, .75F, true);
    private final int materialFlags;
    private final BlockStateModel delegate;

    ConduitBlockModel(BlockStateModel fallback, Function<Identifier, TextureAtlasSprite> sprites) {
        delegate = fallback;
        Map<String, Material.Baked> loaded = new HashMap<>();
        int flags = fallback.materialFlags();
        for (ConduitKind kind : ConduitKind.values()) for (String role : ROLES) {
            String name = kind.name().toLowerCase(Locale.ROOT) + "_" + role;
            var material = new Material.Baked(sprites.apply(BackpackRegistry.id("block/automation/" + name)), false);
            loaded.put(name, material);
            flags |= BakedQuad.MaterialInfo.of(material, material.sprite().transparency(), -1, null, 0).flags();
        }
        materials = Map.copyOf(loaded);
        materialFlags = flags;
    }

    private static ConduitVisualState snapshot(ModelData data) {
        // Forge supplies the model-data snapshot to its native chunk mesher.
        // Never reach into the live block entity from this thread.
        var visual = data.get(ConduitBundleBlockEntity.VISUAL_MODEL);
        return visual == null ? ConduitVisualState.EMPTY : visual;
    }

    @Override public Material.Baked particleMaterial(ModelData data) {
        ConduitVisualState visual = snapshot(data);
        for (ConduitKind kind : ConduitKind.values()) if (visual.has(kind)) return material(kind, "tube");
        return delegate.particleMaterial(data);
    }

    @Override public Material.Baked particleMaterial() { return delegate.particleMaterial(); }
    @Override public int materialFlags() { return materialFlags; }

    @Override public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        delegate.collectParts(random, output);
    }

    @Override public void collectParts(RandomSource random, List<BlockStateModelPart> output, ModelData data) {
        ConduitVisualState visual = snapshot(data);
        if (visual.installedMask() == 0) {
            delegate.collectParts(random, output, data);
        } else output.add(mesh(visual));
    }

    private synchronized BlockStateModelPart mesh(ConduitVisualState state) {
        BlockStateModelPart found = meshes.get(state);
        if (found != null) return found;
        List<BakedQuad> quads = new ArrayList<>();
        var parts = ConduitGeometry.parts(state);
        for (ConduitGeometry.Part part : parts) {
            String role = switch (part.role()) {
                case TUBE -> "tube";
                case HUB, COLLAR -> "collar";
                case ENDPOINT -> state.extracting(part.kind(), part.side())
                        ? state.inserting(part.kind(), part.side()) ? "endpoint_both" : "endpoint_extract"
                        : state.inserting(part.kind(), part.side()) ? "endpoint_insert" : "endpoint";
            };
            Material.Baked material = material(part.kind(), role);
            for (Direction face : Direction.values()) {
                if (!ConduitGeometry.coveredTubeCap(part, face, parts)) quads.add(quad(part.bounds(), face, material));
            }
        }
        Material.Baked particle = material(parts.getFirst().kind(), "tube");
        BlockStateModelPart result = new Geometry(List.copyOf(quads), particle,
                quads.stream().mapToInt(quad -> quad.materialInfo().flags()).reduce(0, (a, b) -> a | b));
        if (meshes.size() >= 512) meshes.remove(meshes.keySet().iterator().next());
        meshes.put(state, result);
        return result;
    }

    static Vector3fc[] vertices(AABB bounds, Direction face) {
        var min = new Vector3f((float) bounds.minX, (float) bounds.minY, (float) bounds.minZ);
        var max = new Vector3f((float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ);
        Vector3fc[] vertices = new Vector3fc[4];
        for (int index = 0; index < vertices.length; index++)
            vertices[index] = FaceInfo.fromFacing(face).getVertexInfo(index).select(min, max);
        return vertices;
    }

    private static BakedQuad quad(AABB bounds, Direction face, Material.Baked material) {
        Vector3fc[] vertices = vertices(bounds, face);
        var sprite = material.sprite();
        // Full texture on each cuboid face, matching the former unit-square UVs.
        long uv0 = UVPair.pack(sprite.getU0(), sprite.getV0());
        long uv1 = UVPair.pack(sprite.getU0(), sprite.getV1());
        long uv2 = UVPair.pack(sprite.getU1(), sprite.getV1());
        long uv3 = UVPair.pack(sprite.getU1(), sprite.getV0());
        return new BakedQuad(vertices[0], vertices[1], vertices[2], vertices[3], uv0, uv1, uv2, uv3, face,
                BakedQuad.MaterialInfo.of(material, sprite.transparency(), -1, null, 0));
    }

    private record Geometry(List<BakedQuad> quads, Material.Baked particleMaterial, int materialFlags)
            implements BlockStateModelPart {
        // Every face is uncullable against neighboring blocks, matching the
        // exposed sub-block lanes; covered caps were removed geometrically above.
        @Override public List<BakedQuad> getQuads(Direction side) { return side == null ? quads : List.of(); }
        @Override public boolean useAmbientOcclusion() { return true; }
    }

    private Material.Baked material(ConduitKind kind, String role) {
        return materials.get(kind.name().toLowerCase(Locale.ROOT) + "_" + role);
    }
}

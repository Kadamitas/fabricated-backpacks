package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitVisualState;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Immutable public snapshots key chunk geometry; there is no per-frame conduit renderer. */
final class ConduitBlockModel extends WrapperBlockStateModel {
    private static final String[] ROLES = {"tube", "collar", "endpoint", "endpoint_insert", "endpoint_extract", "endpoint_both"};
    private final Map<String, Material.Baked> materials;
    private final Map<ConduitVisualState, Mesh> meshes = new LinkedHashMap<>(64, .75F, true);

    ConduitBlockModel(BlockStateModel fallback, ModelBaker baker) {
        super(fallback);
        Map<String, Material.Baked> loaded = new HashMap<>();
        for (ConduitKind kind : ConduitKind.values()) for (String role : ROLES) {
            String name = kind.name().toLowerCase(Locale.ROOT) + "_" + role;
            loaded.put(name, baker.materials().get(new Material(BackpackRegistry.id("block/automation/" + name)),
                    () -> "fabricated_backpacks:conduit_bundle/" + name));
        }
        materials = Map.copyOf(loaded);
    }

    private static ConduitVisualState snapshot(BlockAndTintGetter view, BlockPos position) {
        return view.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle ? bundle.visualState() : ConduitVisualState.EMPTY;
    }
    @Override public Object createGeometryKey(BlockAndTintGetter view, BlockPos position, BlockState state, RandomSource random) {
        return snapshot(view, position);
    }
    @Override public Material.Baked particleMaterial(BlockAndTintGetter view, BlockPos position, BlockState state) {
        ConduitVisualState visual = snapshot(view, position);
        for (ConduitKind kind : ConduitKind.values()) if (visual.has(kind)) return material(kind, "tube");
        return wrapped.particleMaterial();
    }
    @Override public void emitQuads(QuadEmitter emitter, BlockAndTintGetter view, BlockPos position, BlockState state,
                                    RandomSource random, Predicate<Direction> cullTest) {
        ConduitVisualState visual = snapshot(view, position);
        if (visual.installedMask() == 0) {
            super.emitQuads(emitter, view, position, state, random, cullTest);
            return;
        }
        mesh(visual).outputTo(emitter);
    }

    private synchronized Mesh mesh(ConduitVisualState state) {
        Mesh found = meshes.get(state);
        if (found != null) return found;
        var builder = Renderer.get().mutableMesh();
        QuadEmitter emitter = builder.emitter();
        var parts = ConduitGeometry.parts(state);
        for (ConduitGeometry.Part part : parts) {
            String role = switch (part.role()) {
                case TUBE -> "tube";
                case HUB, COLLAR -> "collar";
                case ENDPOINT -> state.extracting(part.kind(), part.side())
                        ? state.inserting(part.kind(), part.side()) ? "endpoint_both" : "endpoint_extract"
                        : state.inserting(part.kind(), part.side()) ? "endpoint_insert" : "endpoint";
            };
            AABB bounds = part.bounds();
            Material.Baked material = material(part.kind(), role);
            for (Direction face : Direction.values()) {
                if (ConduitGeometry.coveredTubeCap(part, face, parts)) continue;
                // Native square supplies six correct face windings. Scale it
                // into the exact same cuboid used by collision and targeting.
                emitter.square(face, 0, 0, 1, 1, 0);
                for (int vertex = 0; vertex < 4; vertex++) {
                    float x = (float) (bounds.minX + emitter.x(vertex) * (bounds.maxX - bounds.minX));
                    float y = (float) (bounds.minY + emitter.y(vertex) * (bounds.maxY - bounds.minY));
                    float z = (float) (bounds.minZ + emitter.z(vertex) * (bounds.maxZ - bounds.minZ));
                    emitter.pos(vertex, x, y, z).normal(vertex, face.getStepX(), face.getStepY(), face.getStepZ());
                }
                emitter.cullFace(null).color(-1, -1, -1, -1).tintIndex(-1).diffuseShade(true).ambientOcclusion(TriState.TRUE);
                emitter.uvUnitSquare();
                emitter.materialBake(material, MutableQuadView.BAKE_NORMALIZED).emit();
            }
        }
        Mesh result = builder.immutableCopy();
        if (meshes.size() >= 512) meshes.remove(meshes.keySet().iterator().next());
        meshes.put(state, result);
        return result;
    }
    private Material.Baked material(ConduitKind kind, String role) {
        return materials.get(kind.name().toLowerCase(Locale.ROOT) + "_" + role);
    }
}

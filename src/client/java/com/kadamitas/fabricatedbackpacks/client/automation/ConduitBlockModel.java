package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitVisualState;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.blockview.v2.FabricBlockView;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable public snapshots key chunk geometry; there is no per-frame conduit renderer. */
final class ConduitBlockModel extends ForwardingBakedModel {
    private static final String[] ROLES = {"tube", "collar", "endpoint", "endpoint_insert", "endpoint_extract", "endpoint_both"};
    private final Map<String, TextureAtlasSprite> materials;
    private final Map<ConduitVisualState, Mesh> meshes = new LinkedHashMap<>(64, .75F, true);

    ConduitBlockModel(BakedModel fallback, Function<Material, TextureAtlasSprite> textureGetter) {
        super(fallback);
        Map<String, TextureAtlasSprite> loaded = new HashMap<>();
        for (ConduitKind kind : ConduitKind.values()) for (String role : ROLES) {
            String name = kind.name().toLowerCase(Locale.ROOT) + "_" + role;
            loaded.put(name, textureGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, BackpackRegistry.id("block/automation/" + name))));
        }
        materials = Map.copyOf(loaded);
    }

    private static ConduitVisualState snapshot(BlockAndTintGetter view, BlockPos position) {
        Object data = ((FabricBlockView) view).getBlockEntityRenderData(position);
        if (data instanceof ConduitVisualState visual) return visual;
        return view.getBlockEntity(position) instanceof ConduitBundleBlockEntity bundle ? bundle.visualState() : ConduitVisualState.EMPTY;
    }
    @Override public boolean isVanillaAdapter() { return false; }
    @Override public void emitBlockQuads(BlockAndTintGetter view, BlockState state, BlockPos position,
                                         Supplier<RandomSource> random, RenderContext context) {
        ConduitVisualState visual = snapshot(view, position);
        if (visual.installedMask() == 0) { super.emitBlockQuads(view, state, position, random, context); return; }
        mesh(visual).outputTo(context.getEmitter());
    }

    private synchronized Mesh mesh(ConduitVisualState state) {
        Mesh found = meshes.get(state);
        if (found != null) return found;
        var builder = java.util.Objects.requireNonNull(RendererAccess.INSTANCE.getRenderer(), "Fabric renderer unavailable").meshBuilder();
        QuadEmitter emitter = builder.getEmitter();
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
            TextureAtlasSprite material = material(part.kind(), role);
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
                emitter.cullFace(null).color(-1, -1, -1, -1).colorIndex(-1);
                emitter.uvUnitSquare();
                emitter.spriteBake(material, MutableQuadView.BAKE_NORMALIZED).emit();
            }
        }
        Mesh result = builder.build();
        if (meshes.size() >= 512) meshes.remove(meshes.keySet().iterator().next());
        meshes.put(state, result);
        return result;
    }
    private TextureAtlasSprite material(ConduitKind kind, String role) {
        return materials.get(kind.name().toLowerCase(Locale.ROOT) + "_" + role);
    }
}

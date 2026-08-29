package com.kadamitas.fabricatedbackpacks.client.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Original cuboids baked once per resource reload; per-engine motion uses pose transforms. */
final class NativeSteamEngineModel {
    private static final Identifier PROFILE = BackpackRegistry.id("steam_engine_profiles.json");
    record MaterialGroup(Model<Unit> model, Identifier texture) {}
    final List<MaterialGroup> wheel, rod, piston;
    final float wheelX, wheelY, wheelZ, radius, rodLength, rodZ;

    private NativeSteamEngineModel(JsonObject profile) {
        if (profile.get("schema").getAsInt() != 1 || !profile.get("phase_units").getAsString().equals("radians"))
            throw new IllegalArgumentException("Unsupported steam engine profile");
        JsonArray atlas = profile.getAsJsonArray("atlas_size");
        if (atlas.size() != 2 || atlas.get(0).getAsInt() != 64 || atlas.get(1).getAsInt() != 64)
            throw new IllegalArgumentException("Steam engine atlases must be 64 by 64");
        float[] center = vector(profile.getAsJsonArray("wheel_center"));
        wheelX = center[0]; wheelY = center[1]; wheelZ = center[2];
        radius = finite(profile, "crank_radius"); rodLength = finite(profile, "rod_length"); rodZ = finite(profile, "rod_z");
        if (radius <= 0 || rodLength <= radius || rodLength > 16) throw new IllegalArgumentException("Invalid engine linkage");
        JsonObject groups = profile.getAsJsonObject("groups");
        JsonObject materials = profile.getAsJsonObject("material_textures");
        wheel = bake(groups.getAsJsonArray("wheel"), materials);
        rod = bake(groups.getAsJsonArray("rod"), materials);
        piston = bake(groups.getAsJsonArray("piston"), materials);
    }

    static NativeSteamEngineModel load(ResourceManager resources) {
        try (Reader reader = resources.openAsReader(PROFILE)) {
            return new NativeSteamEngineModel(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load original steam engine geometry", exception);
        }
    }

    private static List<MaterialGroup> bake(JsonArray parts, JsonObject materials) {
        if (parts.isEmpty() || parts.size() > 96) throw new IllegalArgumentException("Invalid engine part count");
        Map<String, MeshDefinition> meshes = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (JsonElement value : parts) {
            JsonObject part = value.getAsJsonObject();
            String name = part.get("name").getAsString();
            if (!name.matches("[a-z0-9_]+") || !names.add(name)) throw new IllegalArgumentException("Repeated engine part");
            String material = part.get("texture").getAsString();
            if (!materials.has(material)) throw new IllegalArgumentException("Unknown engine material");
            float[] from = vector(part.getAsJsonArray("from")), to = vector(part.getAsJsonArray("to"));
            float[] size = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                size[axis] = to[axis] - from[axis];
                if (from[axis] < -16 || to[axis] > 32 || size[axis] <= 0) throw new IllegalArgumentException("Invalid engine cuboid bounds");
            }
            if (2 * (size[0] + size[2]) > 64 || size[1] + size[2] > 64) throw new IllegalArgumentException("Engine atlas overflow");
            PartPose pose = PartPose.ZERO;
            if (part.has("rotation")) {
                JsonObject rotation = part.getAsJsonObject("rotation");
                float[] pivot = vector(rotation.getAsJsonArray("origin"));
                float angle = finite(rotation, "angle");
                if (!Set.of(-45F, -22.5F, 0F, 22.5F, 45F).contains(angle)) throw new IllegalArgumentException("Unsupported engine element angle");
                float[] angles = new float[3];
                int axis = switch (rotation.get("axis").getAsString()) {
                    case "x" -> 0; case "y" -> 1; case "z" -> 2; default -> throw new IllegalArgumentException("Invalid engine rotation axis");
                };
                angles[axis] = (float) Math.toRadians(angle);
                for (int i = 0; i < 3; i++) from[i] -= pivot[i];
                pose = PartPose.offsetAndRotation(pivot[0], pivot[1], pivot[2], angles[0], angles[1], angles[2]);
            }
            meshes.computeIfAbsent(material, ignored -> new MeshDefinition()).getRoot().addOrReplaceChild(name,
                    CubeListBuilder.create().texOffs(0, 0).addBox(from[0], from[1], from[2], size[0], size[1], size[2]), pose);
        }
        List<MaterialGroup> result = new ArrayList<>();
        for (var entry : meshes.entrySet()) {
            Identifier texture = Identifier.parse(materials.get(entry.getKey()).getAsString());
            result.add(new MaterialGroup(new StaticModel(LayerDefinition.create(entry.getValue(), 64, 64).bakeRoot()),
                    Identifier.fromNamespaceAndPath(texture.getNamespace(), "textures/" + texture.getPath() + ".png")));
        }
        return List.copyOf(result);
    }

    private static float finite(JsonObject object, String key) {
        float value = object.get(key).getAsFloat();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite engine coordinate");
        return value;
    }
    private static float[] vector(JsonArray array) {
        if (array == null || array.size() != 3) throw new IllegalArgumentException("Expected three engine coordinates");
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            result[i] = array.get(i).getAsFloat();
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Non-finite engine vector");
        }
        return result;
    }
    private static final class StaticModel extends Model<Unit> {
        StaticModel(ModelPart root) { super(root, RenderTypes::entityCutout); }
    }
}

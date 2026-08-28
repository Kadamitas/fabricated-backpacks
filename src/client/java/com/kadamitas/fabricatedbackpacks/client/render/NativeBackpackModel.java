package com.kadamitas.fabricatedbackpacks.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bakes original named cuboids into Minecraft-native geometry on resource reload. */
final class NativeBackpackModel {
    private static final Identifier PROFILES = BackpackRegistry.id("backpack_profiles.json");
    private static final Set<String> MATERIALS = Set.of("body", "trim", "lining", "pocket", "fittings");
    private final List<MaterialGroup> groups;
    private final WearTransform wearTransform;
    private final FlapTransform flapTransform;

    private NativeBackpackModel(List<MaterialGroup> groups, WearTransform wearTransform, FlapTransform flapTransform) {
        this.groups = List.copyOf(groups);
        this.wearTransform = wearTransform;
        this.flapTransform = flapTransform;
    }

    List<MaterialGroup> groups() {
        return groups;
    }

    void applyWearTransform(PoseStack poses, boolean armored) {
        poses.translate(wearTransform.x / 16F, wearTransform.y / 16F,
                (wearTransform.z + (armored ? wearTransform.armorClearance : 0)) / 16F);
        poses.scale(wearTransform.scaleX, wearTransform.scaleY, wearTransform.scaleZ);
    }

    void applyFlapTransform(PoseStack poses, float openness) {
        poses.translate(flapTransform.x / 16F, flapTransform.y / 16F, flapTransform.z / 16F);
        poses.mulPose(Axis.XP.rotationDegrees(flapTransform.closedAngle
                + (flapTransform.openAngle - flapTransform.closedAngle) * openness));
        poses.translate(-flapTransform.x / 16F, -flapTransform.y / 16F, -flapTransform.z / 16F);
    }

    /** Invoked by each avatar renderer's reload callback, never during frame submission. */
    static Map<BackpackTier, NativeBackpackModel> load(ResourceManager resources) {
        return load(resources, false);
    }

    static Map<BackpackTier, NativeBackpackModel> loadFlaps(ResourceManager resources) {
        return load(resources, true);
    }

    private static Map<BackpackTier, NativeBackpackModel> load(ResourceManager resources, boolean flapOnly) {
        try (Reader reader = resources.openAsReader(PROFILES)) {
            JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
            if (document.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unknown backpack visual profile schema");
            JsonArray atlas = document.getAsJsonArray("atlas_size");
            if (atlas.size() != 2 || atlas.get(0).getAsInt() != 64 || atlas.get(1).getAsInt() != 64) {
                throw new IllegalArgumentException("Backpack native model atlases must be 64 by 64");
            }
            JsonObject coordinateMap = document.getAsJsonObject("source_to_player_body");
            float[] signs = vector(coordinateMap.getAsJsonArray("axis_sign"));
            for (float sign : signs) if (sign != -1) throw new IllegalArgumentException("Unsupported backpack coordinate axis");
            float[] offset = vector(coordinateMap.getAsJsonArray("offset"));
            Map<BackpackTier, NativeBackpackModel> models = new EnumMap<>(BackpackTier.class);
            JsonArray profiles = document.getAsJsonArray("tiers");
            if (profiles.size() != BackpackTier.values().length) throw new IllegalArgumentException("Backpack visual profiles must cover all tiers");
            for (JsonElement value : profiles) {
                JsonObject profile = value.getAsJsonObject();
                BackpackTier tier = BackpackTier.byId(profile.get("id").getAsString())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown backpack visual tier"));
                if (models.put(tier, bake(profile, document, offset, flapOnly)) != null) {
                    throw new IllegalArgumentException("Duplicate backpack visual tier: " + tier.id());
                }
            }
            return Map.copyOf(models);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load original backpack geometry from " + PROFILES, exception);
        }
    }

    private static NativeBackpackModel bake(JsonObject profile, JsonObject document, float[] offset, boolean flapOnly) {
        Map<String, MeshDefinition> meshes = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        Set<String> moving = new HashSet<>();
        for (JsonElement name : profile.getAsJsonArray("flap_parts")) {
            if (!moving.add(name.getAsString())) throw new IllegalArgumentException("Repeated moving flap part");
        }
        if (!moving.containsAll(Set.of("flap_top", "flap_lip")) || moving.size() > 20)
            throw new IllegalArgumentException("Incomplete or excessive flap geometry");
        JsonArray parts = profile.getAsJsonArray("parts");
        if (parts.isEmpty() || parts.size() > 96) throw new IllegalArgumentException("Invalid backpack geometry part count");
        for (JsonElement value : parts) {
            JsonObject part = value.getAsJsonObject();
            String name = part.get("name").getAsString();
            if (!name.matches("[a-z0-9_]+") || !names.add(name)) throw new IllegalArgumentException("Invalid or repeated backpack part name: " + name);
            if (flapOnly && !moving.contains(name)) continue;
            String material = part.get("texture").getAsString();
            if (!MATERIALS.contains(material)) throw new IllegalArgumentException("Unknown backpack material: " + material);
            float[] sourceFrom = vector(part.getAsJsonArray("from"));
            float[] sourceTo = vector(part.getAsJsonArray("to"));
            float[] from = new float[3];
            float[] size = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                size[axis] = sourceTo[axis] - sourceFrom[axis];
                if (sourceFrom[axis] < -16 || sourceTo[axis] > 32 || size[axis] <= 0) {
                    throw new IllegalArgumentException("Invalid backpack bounds: " + name);
                }
                from[axis] = flapOnly ? sourceFrom[axis] : offset[axis] - sourceTo[axis];
            }
            if (2 * (size[0] + size[2]) > 64 || size[1] + size[2] > 64) {
                throw new IllegalArgumentException("Backpack cube unwrap exceeds its texture atlas: " + name);
            }
            PartPose pose = PartPose.ZERO;
            if (part.has("rotation")) {
                JsonObject rotation = part.getAsJsonObject("rotation");
                float[] sourcePivot = vector(rotation.getAsJsonArray("origin"));
                float[] pivot = new float[3];
                float[] angles = new float[3];
                float degrees = rotation.get("angle").getAsFloat();
                if (!Set.of(-45F, -22.5F, 0F, 22.5F, 45F).contains(degrees)) throw new IllegalArgumentException("Unsupported native backpack rotation");
                int axis = switch (rotation.get("axis").getAsString()) {
                    case "x" -> 0;
                    case "y" -> 1;
                    case "z" -> 2;
                    default -> throw new IllegalArgumentException("Unknown native backpack rotation axis");
                };
                angles[axis] = (float) Math.toRadians(degrees);
                for (int coordinate = 0; coordinate < 3; coordinate++) {
                    pivot[coordinate] = flapOnly ? sourcePivot[coordinate] : offset[coordinate] - sourcePivot[coordinate];
                    from[coordinate] -= pivot[coordinate];
                }
                pose = PartPose.offsetAndRotation(pivot[0], pivot[1], pivot[2], angles[0], angles[1], angles[2]);
            }
            MeshDefinition mesh = meshes.computeIfAbsent(material, ignored -> new MeshDefinition());
            mesh.getRoot().addOrReplaceChild(name, CubeListBuilder.create().texOffs(0, 0)
                    .addBox(from[0], from[1], from[2], size[0], size[1], size[2]), pose);
        }
        if (!names.containsAll(moving)) throw new IllegalArgumentException("Moving flap refers to absent geometry");
        List<MaterialGroup> groups = new ArrayList<>();
        for (Map.Entry<String, MeshDefinition> entry : meshes.entrySet()) {
            String material = entry.getKey();
            int tint = document.getAsJsonObject("material_tints").get(material).getAsInt();
            if (tint < -1 || tint > 1) throw new IllegalArgumentException("Invalid backpack material tint");
            String texture = material.equals("fittings") ? profile.get("fittings_texture").getAsString()
                    : document.getAsJsonObject("material_textures").get(material).getAsString();
            Identifier resource = Identifier.parse(texture);
            Identifier path = Identifier.fromNamespaceAndPath(resource.getNamespace(), "textures/" + resource.getPath() + ".png");
            ModelPart root = LayerDefinition.create(entry.getValue(), 64, 64).bakeRoot();
            groups.add(new MaterialGroup(new StaticMaterialModel(root), path, tint));
        }
        JsonObject transform = document.getAsJsonObject("wear_transform");
        float[] translate = vector(transform.getAsJsonArray("translation_pixels"));
        float[] scale = vector(transform.getAsJsonArray("scale"));
        float clearance = transform.get("armor_clearance_pixels").getAsFloat();
        for (float coordinate : translate) if (Math.abs(coordinate) > 16F) throw new IllegalArgumentException("Backpack torso offset exceeds one block");
        for (float value : scale) if (value < .1F || value > 2F) throw new IllegalArgumentException("Invalid backpack torso scale");
        if (!Float.isFinite(clearance) || clearance < 0F || clearance > 4F) throw new IllegalArgumentException("Invalid backpack armor clearance");
        JsonObject hinge = document.getAsJsonObject("flap_hinge");
        float[] pivot = vector(hinge.getAsJsonArray("origin"));
        float closed = hinge.get("closed_angle").getAsFloat();
        float open = hinge.get("open_angle").getAsFloat();
        if (!hinge.get("axis").getAsString().equals("x") || !Float.isFinite(closed) || !Float.isFinite(open)
                || closed < -45 || closed > 45 || open < closed || open > 90)
            throw new IllegalArgumentException("Invalid backpack flap hinge");
        for (float coordinate : pivot) if (coordinate < -16 || coordinate > 32) throw new IllegalArgumentException("Flap pivot exceeds model bounds");
        return new NativeBackpackModel(groups, new WearTransform(translate[0], translate[1], translate[2], scale[0], scale[1], scale[2], clearance),
                new FlapTransform(pivot[0], pivot[1], pivot[2], closed, open));
    }

    private static float[] vector(JsonArray values) {
        if (values == null || values.size() != 3) throw new IllegalArgumentException("Expected a three-coordinate backpack vector");
        float[] vector = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            vector[axis] = values.get(axis).getAsFloat();
            if (!Float.isFinite(vector[axis])) throw new IllegalArgumentException("Non-finite backpack model coordinate");
        }
        return vector;
    }

    record MaterialGroup(Model<Unit> model, Identifier texture, int tintIndex) {
        int color(BackpackVisualState state) {
            return switch (tintIndex) {
                case 0 -> state.bodyColor();
                case 1 -> state.trimColor();
                default -> -1;
            };
        }
    }

    private record WearTransform(float x, float y, float z, float scaleX, float scaleY, float scaleZ, float armorClearance) {}
    private record FlapTransform(float x, float y, float z, float closedAngle, float openAngle) {}

    private static final class StaticMaterialModel extends Model<Unit> {
        StaticMaterialModel(ModelPart root) {
            super(root, RenderTypes::entityCutout);
        }
    }
}

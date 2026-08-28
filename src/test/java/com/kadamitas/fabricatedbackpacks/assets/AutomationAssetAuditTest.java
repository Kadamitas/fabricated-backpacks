package com.kadamitas.fabricatedbackpacks.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Asset-space checks, not a claim of successful Minecraft rendering. */
class AutomationAssetAuditTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/fabricated_backpacks");
    private static final Set<String> FACES = Set.of("north", "south", "east", "west", "up", "down");

    @Test void bothMetalBlocksExtendTheNativePickaxeSpeedTag() throws Exception {
        JsonObject tag = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json"))).getAsJsonObject();
        assertFalse(tag.get("replace").getAsBoolean(), "Other blocks retain their vanilla pickaxe behavior");
        JsonArray values = tag.getAsJsonArray("values");
        assertEquals(2, values.size());
        Set<String> blocks = new HashSet<>();
        for (JsonElement value : values) {
            String id = value.getAsString();
            assertTrue(blocks.add(id));
            assertTrue(Files.isRegularFile(ASSETS.resolve("blockstates/" + id.split(":", 2)[1] + ".json")));
        }
        assertEquals(Set.of("fabricated_backpacks:conduit_bundle", "fabricated_backpacks:steam_engine"), blocks);
    }

    @Test void newSolidModelsHaveEveryFaceAndUseExistingOpaqueMaterialTiles() throws Exception {
        for (String model : List.of("block/steam_engine", "block/steam_engine_body", "block/conduit_bundle",
                "item/item_conduit", "item/fluid_conduit", "item/energy_conduit")) {
            JsonObject value = json("models/" + model + ".json");
            JsonObject textures = value.getAsJsonObject("textures");
            Set<String> names = new HashSet<>();
            assertFalse(value.getAsJsonArray("elements").isEmpty());
            for (JsonElement element : value.getAsJsonArray("elements")) {
                JsonObject part = element.getAsJsonObject();
                assertTrue(names.add(part.get("name").getAsString()));
                assertEquals(FACES, part.getAsJsonObject("faces").keySet());
                for (JsonElement face : part.getAsJsonObject("faces").asMap().values()) {
                    JsonObject data = face.getAsJsonObject();
                    assertFalse(data.has("cullface"), "Thin geometry keeps its six physical faces");
                    JsonArray uv = data.getAsJsonArray("uv");
                    assertEquals(4, uv.size());
                    for (JsonElement coordinate : uv) assertTrue(coordinate.getAsDouble() >= 0 && coordinate.getAsDouble() <= 16);
                    String alias = data.get("texture").getAsString().substring(1);
                    String path = textures.get(alias).getAsString().split(":", 2)[1];
                    assertTrue(Files.isRegularFile(ASSETS.resolve("textures/" + path + ".png")));
                }
            }
        }
    }

    @Test void animatedPartsAndChunkBodyPartitionTheCompleteOriginalEngine() throws Exception {
        JsonObject profile = json("steam_engine_profiles.json");
        assertEquals(1, profile.get("schema").getAsInt());
        assertEquals("radians", profile.get("phase_units").getAsString());
        JsonObject groups = profile.getAsJsonObject("groups");
        assertEquals(Set.of("body", "wheel", "rod", "piston"), groups.keySet());
        Set<String> all = new HashSet<>();
        for (String group : groups.keySet()) {
            JsonArray parts = groups.getAsJsonArray(group);
            assertTrue(parts.size() > 0 && parts.size() <= 96);
            for (JsonElement value : parts) {
                JsonObject part = value.getAsJsonObject();
                assertTrue(all.add(part.get("name").getAsString()));
                double[] from = vector(part.getAsJsonArray("from")), to = vector(part.getAsJsonArray("to"));
                assertTrue(2 * (to[0] - from[0] + to[2] - from[2]) <= 64);
                assertTrue(to[1] - from[1] + to[2] - from[2] <= 64);
                assertTrue(profile.getAsJsonObject("material_textures").has(part.get("texture").getAsString()));
            }
        }
        assertEquals(all, names(json("models/block/steam_engine.json").getAsJsonArray("elements")));
        assertEquals(names(groups.getAsJsonArray("body")), names(json("models/block/steam_engine_body.json").getAsJsonArray("elements")));
        assertTrue(names(groups.getAsJsonArray("wheel")).containsAll(Set.of("flywheel_rim_0", "flywheel_rim_7", "flywheel_spoke_0", "flywheel_spoke_3", "crank_pin")));
        JsonObject variants = json("blockstates/steam_engine.json").getAsJsonObject("variants");
        assertEquals(8, variants.size());
        for (JsonElement variant : variants.asMap().values()) assertEquals("fabricated_backpacks:block/steam_engine_body", variant.getAsJsonObject().get("model").getAsString());
    }

    @Test void theNativeProfileFitsTheBlockAtEveryFiveDegreePhaseAndItsRodKeepsBothPins() throws Exception {
        JsonObject profile = json("steam_engine_profiles.json");
        JsonObject groups = profile.getAsJsonObject("groups");
        double[] wheel = vector(profile.getAsJsonArray("wheel_center"));
        double radius = profile.get("crank_radius").getAsDouble(), length = profile.get("rod_length").getAsDouble();
        double rodZ = profile.get("rod_z").getAsDouble();
        assertTrue(radius > 0 && length > radius);
        for (int degrees = 0; degrees <= 360; degrees += 5) {
            double angle = Math.toRadians(degrees), pinX = wheel[0] + radius * Math.cos(angle), pinY = wheel[1] + radius * Math.sin(angle);
            double slider = pinX - Math.sqrt(length * length - (pinY - wheel[1]) * (pinY - wheel[1]));
            double rodAngle = Math.atan2(pinY - wheel[1], pinX - slider);
            assertEquals(length, Math.hypot(pinX - slider, pinY - wheel[1]), 1e-10);
            assertEquals(pinX, slider + length * Math.cos(rodAngle), 1e-10);
            assertEquals(pinY, wheel[1] + length * Math.sin(rodAngle), 1e-10);
            for (String group : groups.keySet()) for (JsonElement value : groups.getAsJsonArray(group)) {
                for (double[] point : corners(value.getAsJsonObject())) {
                    double[] transformed = switch (group) {
                        case "wheel" -> add(rotate(point, angle, 2), wheel);
                        case "rod" -> add(rotate(point, rodAngle, 2), new double[]{slider, wheel[1], rodZ});
                        case "piston" -> add(point, new double[]{slider, wheel[1], rodZ});
                        default -> point;
                    };
                    for (double coordinate : transformed) assertTrue(coordinate >= -1e-6 && coordinate <= 16 + 1e-6,
                            group + " exceeds the one-block footprint at " + degrees + " degrees");
                }
            }
        }
    }

    private static Set<String> names(JsonArray parts) {
        Set<String> names = new HashSet<>();
        for (JsonElement part : parts) assertTrue(names.add(part.getAsJsonObject().get("name").getAsString()));
        return names;
    }
    private static List<double[]> corners(JsonObject part) {
        double[] from = vector(part.getAsJsonArray("from")), to = vector(part.getAsJsonArray("to"));
        List<double[]> points = new ArrayList<>();
        for (int bits = 0; bits < 8; bits++) {
            double[] point = new double[3];
            for (int axis = 0; axis < 3; axis++) point[axis] = (bits & 1 << axis) == 0 ? from[axis] : to[axis];
            if (part.has("rotation")) {
                JsonObject rotation = part.getAsJsonObject("rotation");
                double[] origin = vector(rotation.getAsJsonArray("origin"));
                for (int axis = 0; axis < 3; axis++) point[axis] -= origin[axis];
                int axis = switch (rotation.get("axis").getAsString()) { case "x" -> 0; case "y" -> 1; default -> 2; };
                point = add(rotate(point, Math.toRadians(rotation.get("angle").getAsDouble()), axis), origin);
            }
            points.add(point);
        }
        return points;
    }
    private static double[] rotate(double[] point, double angle, int axis) {
        double[] result = point.clone();
        int first = (axis + 1) % 3, second = (axis + 2) % 3;
        result[first] = point[first] * Math.cos(angle) - point[second] * Math.sin(angle);
        result[second] = point[first] * Math.sin(angle) + point[second] * Math.cos(angle);
        return result;
    }
    private static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double[] vector(JsonArray array) { return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()}; }
    private static JsonObject json(String name) throws Exception { return JsonParser.parseString(Files.readString(ASSETS.resolve(name))).getAsJsonObject(); }
}

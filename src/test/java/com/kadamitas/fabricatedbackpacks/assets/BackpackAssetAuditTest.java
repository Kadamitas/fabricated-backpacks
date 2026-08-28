package com.kadamitas.fabricatedbackpacks.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Resource and geometry audits. A passing audit is not an in-game visual pass. */
class BackpackAssetAuditTest {
    private static final double GEOMETRY_EPSILON = 1e-6;
    private static final String NAMESPACE = "fabricated_backpacks";
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path RESOURCES = ROOT.resolve("src/main/resources");
    private static final Path ASSETS = RESOURCES.resolve("assets/" + NAMESPACE);
    private static final Path DATA = RESOURCES.resolve("data/" + NAMESPACE);
    private static final List<String> TIERS = List.of("backpack", "copper_backpack", "iron_backpack", "gold_backpack", "diamond_backpack", "netherite_backpack");
    private static final Set<String> FACES = Set.of("north", "south", "east", "west", "up", "down");
    private static final Set<String> ESSENTIAL_PARTS = Set.of("body_floor", "body_left", "body_right", "body_front", "body_back", "front_pocket",
            "flap_top", "flap_lip", "strap_left_long", "strap_right_long", "handle_top");

    static Stream<String> tiers() {
        return TIERS.stream();
    }

    @Test
    void everyJsonParsesAndCatalogExactlyMatchesItemDefinitions() throws IOException {
        for (Path directory : List.of(ASSETS, DATA)) {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                    assertNotNull(json(file), file.toString());
                }
            }
        }
        Set<String> expected = registeredItems();
        assertEquals(71, expected.size());
        try (Stream<Path> files = Files.list(ASSETS.resolve("items"))) {
            Set<String> actual = new HashSet<>();
            files.forEach(path -> actual.add(path.getFileName().toString().replace(".json", "")));
            assertEquals(expected, actual);
        }
        assertEquals(54, UpgradeKind.values().length);
    }

    @Test
    void generatedFilesAndTheirInputsMatchManifestHashes() throws Exception {
        JsonObject manifest = json(ASSETS.resolve("asset_manifest.json"));
        assertEquals("MIT", manifest.get("license").getAsString());
        assertEquals(71, manifest.get("registered_item_count").getAsInt());
        for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject("files").entrySet()) {
            Path path = RESOURCES.resolve(entry.getKey()).normalize();
            assertTrue(path.startsWith(RESOURCES), "Manifest cannot escape resources: " + entry.getKey());
            assertTrue(Files.isRegularFile(path), entry.getKey());
            assertEquals(entry.getValue().getAsString(), sha256(path), "Regenerate stale resource: " + entry.getKey());
        }
        JsonObject projectArtifacts = manifest.getAsJsonObject("project_artifacts");
        assertEquals(Set.of("docs/media/project-icon.png"), projectArtifacts.keySet());
        for (Map.Entry<String, JsonElement> entry : projectArtifacts.entrySet()) {
            Path path = ROOT.resolve(entry.getKey()).normalize();
            assertTrue(path.startsWith(ROOT.resolve("docs/media")), "Branding must remain in its declared output directory");
            assertEquals(entry.getValue().getAsString(), sha256(path), "Regenerate stale project art: " + entry.getKey());
        }
        for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject("inputs").entrySet()) {
            Path path = ROOT.resolve(entry.getKey()).normalize();
            assertTrue(path.startsWith(ROOT), "Manifest input cannot escape repository");
            assertEquals(entry.getValue().getAsString(), sha256(path), "Generator input changed; rerun tools/generate_assets.py: " + entry.getKey());
        }
    }

    @Test
    void publishingIconKeepsOpaquePixelClustersAndReadableColorContrast() throws IOException {
        Path path = ROOT.resolve("docs/media/project-icon.png");
        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image);
        assertEquals(512, image.getWidth());
        assertEquals(512, image.getHeight());
        Set<Integer> palette = new HashSet<>();
        int warm = 0;
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
            int pixel = image.getRGB(x, y);
            assertEquals(255, pixel >>> 24);
            assertEquals(image.getRGB(x / 4 * 4, y / 4 * 4), pixel, "Branding uses exact four-pixel clusters");
            palette.add(pixel);
            int red = pixel >> 16 & 255, green = pixel >> 8 & 255, blue = pixel & 255;
            if (red > green * 1.15 && green > blue * 1.15) warm++;
        }
        assertTrue(palette.size() > 35);
        assertTrue(warm > 512 * 512 * .2 && warm < 512 * 512 * .7);
        int corner = image.getRGB(0, 0);
        assertTrue((corner >> 16 & 255) < (corner >> 8 & 255));
        assertTrue((corner >> 16 & 255) < (corner & 255));
    }

    @Test
    void allItemModelsAndTextureAliasesResolveWithoutCycles() throws IOException {
        for (String item : registeredItems()) {
            JsonObject definition = json(ASSETS.resolve("items/" + item + ".json")).getAsJsonObject("model");
            assertEquals("minecraft:model", definition.get("type").getAsString(), item);
            String reference = definition.get("model").getAsString();
            JsonObject resolved = resolveModel(reference, new HashSet<>());
            JsonObject textures = resolved.getAsJsonObject("textures");
            assertNotNull(textures, item + " has no textures");
            for (String alias : textures.keySet()) {
                String texture = resolveTexture("#" + alias, textures, new HashSet<>());
                assertTrue(Files.isRegularFile(texturePath(texture)), item + ": " + texture);
            }
        }
        JsonObject broken = new JsonObject();
        broken.addProperty("one", "#two");
        broken.addProperty("two", "#one");
        assertThrows(IllegalArgumentException.class, () -> resolveTexture("#one", broken, new HashSet<>()));
        assertThrows(IllegalArgumentException.class, () -> resolveTexture("#missing", broken, new HashSet<>()));
    }

    @ParameterizedTest
    @MethodSource("tiers")
    void eachTierHasCompleteClosedOpenAndFacingModels(String tier) throws IOException {
        JsonObject variants = json(ASSETS.resolve("blockstates/" + tier + ".json")).getAsJsonObject("variants");
        assertEquals(8, variants.size());
        Map<String, Integer> rotations = Map.of("north", 0, "east", 90, "south", 180, "west", 270);
        for (Map.Entry<String, Integer> direction : rotations.entrySet()) {
            for (boolean open : List.of(false, true)) {
                JsonObject variant = variants.getAsJsonObject("facing=" + direction.getKey() + ",open=" + open);
                assertNotNull(variant, tier + " facing=" + direction + " open=" + open);
                assertEquals(direction.getValue().intValue(), variant.get("y").getAsInt());
                assertFalse(variant.get("uvlock").getAsBoolean());
                assertEquals(NAMESPACE + ":block/" + tier + "_body", variant.get("model").getAsString());
                // Waterlogging is deliberately not part of the variant selector.
                // The same geometry is used for both waterlogged property values.
            }
        }
        for (String state : List.of("closed", "open")) {
            JsonObject model = json(ASSETS.resolve("models/block/" + tier + "_" + state + ".json"));
            Set<String> parts = new HashSet<>();
            Set<Integer> tintIndices = new HashSet<>();
            for (JsonElement elementValue : model.getAsJsonArray("elements")) {
                JsonObject element = elementValue.getAsJsonObject();
                String name = element.get("name").getAsString();
                assertTrue(parts.add(name), "Duplicate geometry part: " + name);
                double[] from = vector(element.getAsJsonArray("from"));
                double[] to = vector(element.getAsJsonArray("to"));
                for (int axis = 0; axis < 3; axis++) {
                    assertTrue(from[axis] < to[axis], name + " has zero/negative volume");
                    assertTrue(from[axis] >= -16 && to[axis] <= 32, name + " exceeds native model bounds");
                }
                JsonObject faces = element.getAsJsonObject("faces");
                assertEquals(FACES, faces.keySet(), name + " is missing a closed face");
                for (Map.Entry<String, JsonElement> faceEntry : faces.entrySet()) {
                    JsonObject face = faceEntry.getValue().getAsJsonObject();
                    assertFalse(face.has("cullface"), name + " sculpted face cannot cull at block boundary");
                    JsonArray uv = face.getAsJsonArray("uv");
                    assertEquals(4, uv.size());
                    for (JsonElement coordinate : uv) {
                        assertTrue(Double.isFinite(coordinate.getAsDouble()));
                        assertTrue(coordinate.getAsDouble() >= 0 && coordinate.getAsDouble() <= 16, name + " UV out of texture bounds");
                    }
                    assertTrue(uv.get(0).getAsDouble() < uv.get(2).getAsDouble());
                    assertTrue(uv.get(1).getAsDouble() < uv.get(3).getAsDouble());
                    int tint = face.has("tintindex") ? face.get("tintindex").getAsInt() : -1;
                    assertTrue(tint >= -1 && tint <= 1);
                    tintIndices.add(tint);
                    String texture = resolveTexture(face.get("texture").getAsString(), model.getAsJsonObject("textures"), new HashSet<>());
                    BufferedImage image = ImageIO.read(texturePath(texture).toFile());
                    assertNotNull(image, texture);
                    assertEquals(16, image.getWidth());
                    assertEquals(16, image.getHeight());
                }
                if (element.has("rotation")) {
                    JsonObject rotation = element.getAsJsonObject("rotation");
                    assertTrue(Set.of("x", "y", "z").contains(rotation.get("axis").getAsString()));
                    assertTrue(Set.of(-45.0, -22.5, 0.0, 22.5, 45.0).contains(rotation.get("angle").getAsDouble()));
                    vector(rotation.getAsJsonArray("origin"));
                    assertFalse(rotation.get("rescale").getAsBoolean());
                }
            }
            assertTrue(parts.containsAll(ESSENTIAL_PARTS));
            assertEquals(Set.of(-1, 0, 1), tintIndices, "Body, trim and uncolored fittings must all exist");
            for (JsonElement displayValue : model.getAsJsonObject("display").asMap().values()) {
                JsonObject display = displayValue.getAsJsonObject();
                vector(display.getAsJsonArray("rotation"));
                vector(display.getAsJsonArray("translation"));
                assertTrue(Arrays.stream(vector(display.getAsJsonArray("scale"))).allMatch(value -> value > 0 && value <= 4));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("tiers")
    void eachTierItemUsesIndependentBodyAndTrimTintSources(String tier) throws IOException {
        JsonObject definition = json(ASSETS.resolve("items/" + tier + ".json")).getAsJsonObject("model");
        JsonArray tints = definition.getAsJsonArray("tints");
        assertEquals(2, tints.size());
        for (int index = 0; index < 2; index++) {
            JsonObject tint = tints.get(index).getAsJsonObject();
            assertEquals("minecraft:custom_model_data", tint.get("type").getAsString());
            assertEquals(index, tint.get("index").getAsInt());
            assertEquals(index == 0 ? 0xB97843 : 0x503B36, tint.get("default").getAsInt());
        }
        JsonObject model = json(ASSETS.resolve("models/item/" + tier + ".json"));
        assertEquals(NAMESPACE + ":block/" + tier + "_closed", model.get("parent").getAsString());
    }

    @Test
    void texturesHaveExpectedResolutionAlphaAndDistinctUpgradeArt() throws Exception {
        Set<String> iconHashes = new HashSet<>();
        int itemTextures = 0;
        try (Stream<Path> files = Files.walk(ASSETS)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".png")).toList()) {
                BufferedImage image = ImageIO.read(path.toFile());
                assertNotNull(image, path.toString());
                String relative = ASSETS.relativize(path).toString().replace('\\', '/');
                int size = relative.equals("icon.png") ? 128 : relative.contains("/entity/") ? 64 : 16;
                assertEquals(size, image.getWidth(), relative);
                assertEquals(size, image.getHeight(), relative);
                int occupied = 0;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int alpha = image.getRGB(x, y) >>> 24;
                        assertTrue(alpha == 0 || alpha == 255, relative + " has unwanted fractional alpha");
                        if (relative.startsWith("textures/block/") || relative.startsWith("textures/entity/")) {
                            assertEquals(255, alpha, relative + " material must be opaque");
                        }
                        if (alpha != 0) occupied++;
                    }
                }
                if (relative.startsWith("textures/item/")) {
                    itemTextures++;
                    assertEquals(0, image.getRGB(0, 0) >>> 24);
                    assertTrue(occupied > 130 && occupied < 230, relative + " bad icon silhouette");
                    assertTrue(iconHashes.add(sha256(path)), relative + " duplicates another upgrade icon");
                }
            }
        }
        assertEquals(65, itemTextures);
    }

    @Test
    void languageNamesAndDescriptionsCoverEveryItem() throws IOException {
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        for (String item : registeredItems()) {
            assertText(lang, "item." + NAMESPACE + "." + item);
            if (TIERS.contains(item)) {
                assertText(lang, "block." + NAMESPACE + "." + item);
            } else {
                assertText(lang, "tooltip." + NAMESPACE + "." + item);
            }
        }
        assertTrue(lang.get("tooltip.fabricated_backpacks.advanced_jukebox_upgrade").getAsString().contains("twelve"));
    }

    @Test
    void recipesCoverSurvivalCatalogAndUsePreservingBackpackSerializers() throws IOException {
        Set<String> allItems = registeredItems();
        Set<String> craftable = new HashSet<>();
        try (Stream<Path> files = Files.list(DATA.resolve("recipe"))) {
            for (Path file : files.toList()) {
                JsonObject recipe = json(file);
                if (file.getFileName().toString().equals("dye_backpack.json")) {
                    assertEquals(Set.of("type"), recipe.keySet());
                    assertEquals(NAMESPACE + ":dye_backpack", recipe.get("type").getAsString());
                    continue;
                }
                String result = recipe.getAsJsonObject("result").get("id").getAsString();
                assertTrue(result.startsWith(NAMESPACE + ":"));
                String resultItem = result.substring(NAMESPACE.length() + 1);
                assertTrue(allItems.contains(resultItem), file.toString());
                assertEquals(1, recipe.getAsJsonObject("result").get("count").getAsInt());
                craftable.add(resultItem);
                if (TIERS.contains(resultItem) && !resultItem.equals("backpack")) {
                    assertEquals(NAMESPACE + (resultItem.equals("netherite_backpack") ? ":backpack_smithing" : ":backpack_upgrade"), recipe.get("type").getAsString());
                    if (recipe.has("source")) {
                        String source = recipe.get("source").getAsString();
                        assertTrue(TIERS.stream().map(id -> NAMESPACE + ":" + id).anyMatch(source::equals));
                        assertTrue(recipe.getAsJsonObject("key").asMap().values().stream().anyMatch(value -> value.getAsString().equals(source)));
                    }
                }
                List<JsonElement> ingredients = new ArrayList<>();
                if (recipe.has("key")) ingredients.addAll(recipe.getAsJsonObject("key").asMap().values());
                if (recipe.has("ingredients")) recipe.getAsJsonArray("ingredients").forEach(ingredients::add);
                for (String key : List.of("template", "base", "addition")) {
                    if (recipe.has(key)) ingredients.add(recipe.get(key));
                }
                for (JsonElement ingredient : ingredients) {
                    assertTrue(ingredient.isJsonPrimitive() && ingredient.getAsJsonPrimitive().isString(), "26.2 ingredients must use modern string/tag form: " + file);
                    String id = ingredient.getAsString();
                    assertTrue(id.startsWith("minecraft:") || id.startsWith(NAMESPACE + ":"), "Unexpected external recipe dependency: " + id);
                    if (id.startsWith(NAMESPACE + ":")) assertTrue(allItems.contains(id.substring(NAMESPACE.length() + 1)), id);
                }
            }
        }
        Set<String> expected = new HashSet<>(allItems);
        expected.remove("infinity_upgrade");
        expected.remove("stack_upgrade_omega_tier");
        assertEquals(expected, craftable);
    }

    @Test
    void blockLootDoesNotDuplicateThePersistedBackpackStack() throws IOException {
        for (String tier : TIERS) {
            JsonObject table = json(DATA.resolve("loot_table/blocks/" + tier + ".json"));
            assertEquals("minecraft:block", table.get("type").getAsString());
            assertTrue(table.getAsJsonArray("pools").isEmpty(), "Backpack block code owns the full persisted stack drop");
        }
    }

    @Test
    void goldBackpackExtendsTheNativePiglinSafetyTag() throws IOException {
        JsonObject tag = json(RESOURCES.resolve("data/minecraft/tags/item/piglin_safe_armor.json"));
        assertFalse(tag.get("replace").getAsBoolean(), "Keep vanilla and other data-pack armor entries");
        assertEquals(List.of("fabricated_backpacks:gold_backpack"),
                tag.getAsJsonArray("values").asList().stream().map(JsonElement::getAsString).toList());
    }

    @Test
    void nativeWornProfilesMatchProductionGeometryAndFitTheirTextureAtlas() throws IOException {
        JsonObject profile = json(ASSETS.resolve("backpack_profiles.json"));
        assertEquals(1, profile.get("schema").getAsInt());
        assertEquals(64, profile.getAsJsonArray("atlas_size").get(0).getAsInt());
        assertEquals(64, profile.getAsJsonArray("atlas_size").get(1).getAsInt());
        assertEquals(6, profile.getAsJsonArray("tiers").size());
        for (JsonElement tierValue : profile.getAsJsonArray("tiers")) {
            JsonObject tier = tierValue.getAsJsonObject();
            String id = tier.get("id").getAsString();
            JsonArray parts = tier.getAsJsonArray("parts");
            JsonArray expected = json(ASSETS.resolve("models/block/" + id + "_closed.json")).getAsJsonArray("elements");
            assertEquals(expected.size(), parts.size());
            for (int index = 0; index < parts.size(); index++) {
                JsonObject part = parts.get(index).getAsJsonObject();
                JsonObject element = expected.get(index).getAsJsonObject();
                for (String field : List.of("name", "from", "to")) assertEquals(element.get(field), part.get(field));
                double[] from = vector(part.getAsJsonArray("from"));
                double[] to = vector(part.getAsJsonArray("to"));
                double width = to[0] - from[0], height = to[1] - from[1], depth = to[2] - from[2];
                assertTrue(2 * (width + depth) <= 64, "Unwrapped native cube UV width exceeds atlas: " + part.get("name"));
                assertTrue(height + depth <= 64, "Unwrapped native cube UV height exceeds atlas: " + part.get("name"));
                assertTrue(profile.getAsJsonObject("material_tints").has(part.get("texture").getAsString()));
            }
            assertTrue(Files.isRegularFile(texturePath(tier.get("fittings_texture").getAsString())));
        }
    }

    @Test
    void nativeWornProfileCoversTheTorsoWithContactAndAttachedPockets() throws IOException {
        JsonObject profile = json(ASSETS.resolve("backpack_profiles.json"));
        JsonObject transform = profile.getAsJsonObject("wear_transform");
        double[] offset = vector(profile.getAsJsonObject("source_to_player_body").getAsJsonArray("offset"));
        double[] translate = vector(transform.getAsJsonArray("translation_pixels"));
        double[] scale = vector(transform.getAsJsonArray("scale"));
        double armorClearance = transform.get("armor_clearance_pixels").getAsDouble();
        assertArrayEquals(new double[]{-1, -1, -1}, vector(profile.getAsJsonObject("source_to_player_body").getAsJsonArray("axis_sign")), GEOMETRY_EPSILON);
        assertArrayEquals(new double[]{.90, 1.00, .70}, scale, GEOMETRY_EPSILON);
        assertArrayEquals(new double[]{0, 0, .70}, translate, GEOMETRY_EPSILON);
        assertEquals(1, armorClearance, GEOMETRY_EPSILON);
        for (JsonElement tierValue : profile.getAsJsonArray("tiers")) {
            JsonObject tier = tierValue.getAsJsonObject();
            String id = tier.get("id").getAsString();
            Map<String, WornBounds> parts = new HashMap<>();
            for (JsonElement value : tier.getAsJsonArray("parts")) {
                JsonObject part = value.getAsJsonObject();
                parts.put(part.get("name").getAsString(), wornBounds(part, offset, translate, scale));
            }
            WornBounds complete = parts.values().stream().reduce(WornBounds::union).orElseThrow();
            WornBounds floor = part(parts, "body_floor"), flap = part(parts, "flap_top");
            // The 8-by-12-pixel torso is a coverage reference, not a box that must contain the backpack.
            assertEquals(9, floor.width(), GEOMETRY_EPSILON, id + ": shell covers the torso with half a pixel beyond each side");
            assertEquals(9.45, flap.width(), GEOMETRY_EPSILON, id + ": the lid overhangs the shell");
            assertEquals(11.625, floor.maxY() - flap.minY(), GEOMETRY_EPSILON, id + ": shell and lid cover almost the full torso height");
            assertEquals(.25, complete.minY(), GEOMETRY_EPSILON, id + ": handle starts just below the shoulder line");
            assertEquals(13.25, complete.maxY(), GEOMETRY_EPSILON, id + ": the base extends below the torso");
            assertEquals(-6.525, complete.minX(), GEOMETRY_EPSILON, id + ": side clasp defines the left silhouette");
            assertEquals(6.525, complete.maxX(), GEOMETRY_EPSILON, id + ": side clasp defines the right silhouette");
            assertEquals(id.equals("diamond_backpack") ? 10.4125 : 10.325, complete.maxZ(), GEOMETRY_EPSILON,
                    id + ": the front latch or diamond seal defines the pack depth");

            double bareGap = complete.minZ() - 2;
            double armoredGap = complete.minZ() + armorClearance - 3;
            assertEquals(.275, bareGap, GEOMETRY_EPSILON, id + ": straps sit near the bare torso without intersecting it");
            assertEquals(bareGap, armoredGap, GEOMETRY_EPSILON, id + ": armor translation preserves the same contact gap");
            WornBounds back = part(parts, "body_back");
            for (String side : List.of("left", "right")) {
                assertEquals(complete.minZ(), part(parts, "strap_" + side + "_adjuster").minZ(), GEOMETRY_EPSILON,
                        id + ": the strap adjuster is the closest surface to the wearer");
                assertEquals(.2625, back.minZ() - part(parts, "strap_" + side + "_long").maxZ(), GEOMETRY_EPSILON,
                        id + ": the long strap retains an actual air gap from the shell");
                assertAttached(part(parts, "strap_" + side + "_upper"), back, id + ": upper strap joins the shell");
                assertAttached(part(parts, "strap_" + side + "_lower"), back, id + ": lower strap joins the shell");
            }

            WornBounds front = part(parts, "body_front"), pocket = part(parts, "front_pocket");
            assertEquals(2.1, pocket.maxZ() - front.maxZ(), GEOMETRY_EPSILON, id + ": front pocket has readable depth beyond the shell");
            assertAttached(pocket, front, id + ": front pocket is attached rather than floating");
            assertEquals(pocket.minY(), part(parts, "pocket_flap").maxY(), GEOMETRY_EPSILON, id + ": pocket flap meets its opening");

            Set<String> moving = new HashSet<>();
            tier.getAsJsonArray("flap_parts").forEach(value -> moving.add(value.getAsString()));
            double lidBottom = moving.stream().map(name -> part(parts, name)).mapToDouble(WornBounds::maxY).max().orElseThrow();
            for (String piece : List.of("body", "cap", "strap", "clasp", "welt")) {
                String leftName = "side_pocket_left_" + piece, rightName = "side_pocket_right_" + piece;
                WornBounds left = part(parts, leftName), right = part(parts, rightName);
                assertFalse(moving.contains(leftName) || moving.contains(rightName), id + ": side pockets belong to the fixed shell");
                assertArrayEquals(new double[]{-left.maxX(), left.minY(), left.minZ(), -left.minX(), left.maxY(), left.maxZ()},
                        new double[]{right.minX(), right.minY(), right.minZ(), right.maxX(), right.maxY(), right.maxZ()}, GEOMETRY_EPSILON,
                        id + ": mirrored side-pocket " + piece);
            }
            for (String side : List.of("left", "right")) {
                WornBounds sideBody = part(parts, "side_pocket_" + side + "_body");
                WornBounds sideCap = part(parts, "side_pocket_" + side + "_cap");
                WornBounds sideStrap = part(parts, "side_pocket_" + side + "_strap");
                assertAttached(sideBody, part(parts, "body_" + side), id + ": side pocket joins the main wall");
                assertEquals(sideBody.minY(), sideCap.maxY(), GEOMETRY_EPSILON, id + ": side cap closes the pouch opening");
                assertEquals(1, sideCap.minY() - lidBottom, GEOMETRY_EPSILON, id + ": side cap clears the closed lid and tabs");
                assertAttached(sideStrap, sideBody, id + ": side strap contacts the pouch");
                assertAttached(part(parts, "side_pocket_" + side + "_clasp"), sideStrap, id + ": clasp is anchored to its strap");
                assertAttached(part(parts, "side_pocket_" + side + "_welt"), sideBody, id + ": lower welt follows the pouch base");
            }
        }
    }

    @Test
    void utilityToolRulesHaveExplicitManualSelectionAndBoundedNativeMatchers() throws IOException {
        for (String name : List.of("shearing", "tilling", "flattening")) {
            JsonObject rule = json(DATA.resolve("backpack_tools/" + name + ".json"));
            assertTrue(rule.get("manual_only").getAsBoolean());
            assertFalse(rule.get("require_correct_tool").getAsBoolean());
            assertEquals(name.equals("shearing") ? 100 : 10, rule.get("priority").getAsInt());
            assertFalse(rule.getAsJsonArray("items").isEmpty());
            assertTrue(rule.has("blocks") || rule.has("entities"));
            for (String key : List.of("items", "blocks", "entities")) if (rule.has(key)) {
                JsonArray matchers = rule.getAsJsonArray(key);
                assertTrue(matchers.size() > 0 && matchers.size() <= 6);
                for (JsonElement matcher : matchers) assertTrue(matcher.getAsString().matches("#?minecraft:[a-z0-9_/]+"));
            }
        }
    }

    @Test
    void staticShellAndAnimatedFlapHaveNoMissingOrDuplicateParts() throws IOException {
        JsonObject profile = json(ASSETS.resolve("backpack_profiles.json"));
        assertEquals(com.kadamitas.fabricatedbackpacks.block.BackpackLidAnimation.DURATION_TICKS,
                profile.getAsJsonObject("flap_hinge").get("duration_ticks").getAsInt());
        for (JsonElement tierValue : profile.getAsJsonArray("tiers")) {
            JsonObject tier = tierValue.getAsJsonObject();
            String id = tier.get("id").getAsString();
            JsonObject full = json(ASSETS.resolve("models/block/" + id + "_closed.json"));
            JsonObject shell = json(ASSETS.resolve("models/block/" + id + "_body.json"));
            Map<String, JsonElement> parts = new java.util.HashMap<>();
            for (JsonElement part : full.getAsJsonArray("elements")) parts.put(part.getAsJsonObject().get("name").getAsString(), part);
            Set<String> moving = new HashSet<>();
            for (JsonElement name : tier.getAsJsonArray("flap_parts")) assertTrue(moving.add(name.getAsString()));
            Set<String> present = new HashSet<>(moving);
            for (JsonElement part : shell.getAsJsonArray("elements")) {
                String name = part.getAsJsonObject().get("name").getAsString();
                assertTrue(present.add(name), "A static part cannot also be animated: " + name);
                assertEquals(parts.get(name), part, "The shell keeps the original face/UV/material data");
            }
            assertEquals(parts.keySet(), present);
            assertTrue(moving.containsAll(Set.of("flap_top", "flap_lip", "flap_tab_left", "flap_tab_right")));
            assertEquals(NAMESPACE + ":block/" + id + "_body", tier.get("body_model").getAsString());
        }
    }

    private record WornBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double width() { return maxX - minX; }
        WornBounds union(WornBounds other) {
            return new WornBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }
    }

    private static WornBounds part(Map<String, WornBounds> parts, String name) {
        WornBounds result = parts.get(name);
        assertNotNull(result, "Missing worn geometry part: " + name);
        return result;
    }

    private static void assertAttached(WornBounds first, WornBounds second, String message) {
        assertTrue(Math.min(first.maxX(), second.maxX()) - Math.max(first.minX(), second.minX()) > GEOMETRY_EPSILON
                        && Math.min(first.maxY(), second.maxY()) - Math.max(first.minY(), second.minY()) > GEOMETRY_EPSILON
                        && Math.min(first.maxZ(), second.maxZ()) - Math.max(first.minZ(), second.minZ()) > GEOMETRY_EPSILON,
                message);
    }

    /** Apply the native part rotation before the player-body offset, scale and wear translation. */
    private static WornBounds wornBounds(JsonObject part, double[] offset, double[] translate, double[] scale) {
        double[] from = vector(part.getAsJsonArray("from")), to = vector(part.getAsJsonArray("to"));
        JsonObject rotation = part.has("rotation") ? part.getAsJsonObject("rotation") : null;
        double[] pivot = rotation == null ? null : vector(rotation.getAsJsonArray("origin"));
        int axis = rotation == null ? 0 : switch (rotation.get("axis").getAsString()) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> throw new IllegalArgumentException("Unknown worn part rotation axis");
        };
        double angle = rotation == null ? 0 : Math.toRadians(rotation.get("angle").getAsDouble());
        if (rotation != null && rotation.has("rescale")) assertFalse(rotation.get("rescale").getAsBoolean());
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int corner = 0; corner < 8; corner++) {
            double[] point = new double[3];
            for (int coordinate = 0; coordinate < 3; coordinate++)
                point[coordinate] = (corner & (1 << coordinate)) == 0 ? from[coordinate] : to[coordinate];
            if (rotation != null) {
                int first = (axis + 1) % 3, second = (axis + 2) % 3;
                double a = point[first] - pivot[first], b = point[second] - pivot[second];
                point[first] = pivot[first] + a * Math.cos(angle) - b * Math.sin(angle);
                point[second] = pivot[second] + a * Math.sin(angle) + b * Math.cos(angle);
            }
            for (int coordinate = 0; coordinate < 3; coordinate++) {
                double value = translate[coordinate] + (offset[coordinate] - point[coordinate]) * scale[coordinate];
                min[coordinate] = Math.min(min[coordinate], value);
                max[coordinate] = Math.max(max[coordinate], value);
            }
        }
        return new WornBounds(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static Set<String> registeredItems() {
        Set<String> items = new LinkedHashSet<>(TIERS);
        items.add("upgrade_base");
        Arrays.stream(UpgradeKind.values()).map(UpgradeKind::id).forEach(items::add);
        for (int from = 0; from < 4; from++) {
            for (int to = from + 1; to <= 4; to++) {
                items.add((from == 0 ? "stack_upgrade_starter_tier" : "stack_upgrade_tier_" + from) + "_to_tier_" + to + "_conversion");
            }
        }
        return items;
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static JsonObject resolveModel(String reference, Set<String> visited) throws IOException {
        if (!visited.add(reference)) throw new IllegalArgumentException("Model parent cycle: " + reference);
        if (reference.equals("minecraft:item/generated")) return new JsonObject();
        if (!reference.startsWith(NAMESPACE + ":")) throw new IllegalArgumentException("Unsupported model namespace: " + reference);
        JsonObject model = json(ASSETS.resolve("models/" + reference.substring(NAMESPACE.length() + 1) + ".json"));
        if (!model.has("parent")) return model;
        JsonObject merged = resolveModel(model.get("parent").getAsString(), visited).deepCopy();
        for (Map.Entry<String, JsonElement> entry : model.entrySet()) {
            if (entry.getKey().equals("textures") && merged.has("textures")) {
                for (Map.Entry<String, JsonElement> texture : entry.getValue().getAsJsonObject().entrySet()) {
                    merged.getAsJsonObject("textures").add(texture.getKey(), texture.getValue());
                }
            } else {
                merged.add(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private static String resolveTexture(String reference, JsonObject textures, Set<String> visited) {
        if (!reference.startsWith("#")) return reference;
        String key = reference.substring(1);
        if (!visited.add(key)) throw new IllegalArgumentException("Texture alias cycle: " + key);
        if (!textures.has(key)) throw new IllegalArgumentException("Missing texture alias: " + key);
        return resolveTexture(textures.get(key).getAsString(), textures, visited);
    }

    private static Path texturePath(String resource) {
        if (!resource.startsWith(NAMESPACE + ":")) throw new IllegalArgumentException("Unexpected texture namespace: " + resource);
        Path path = ASSETS.resolve("textures/" + resource.substring(NAMESPACE.length() + 1) + ".png").normalize();
        if (!path.startsWith(ASSETS.resolve("textures"))) throw new IllegalArgumentException("Texture escapes asset root: " + resource);
        return path;
    }

    private static double[] vector(JsonArray values) {
        assertNotNull(values);
        assertEquals(3, values.size());
        double[] result = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            result[axis] = values.get(axis).getAsDouble();
            assertTrue(Double.isFinite(result[axis]));
        }
        return result;
    }

    private static void assertText(JsonObject object, String key) {
        assertTrue(object.has(key), "Missing language key: " + key);
        assertFalse(object.get(key).getAsString().isBlank(), "Empty language value: " + key);
    }
}

package com.kadamitas.fabricatedbackpacks.config;

import com.kadamitas.fabricatedbackpacks.domain.StackCapacity;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Server-owned upgrade bounds. Saved item preferences cannot increase these limits. */
public record UpgradeConfig(Map<String, Filter> filters, Map<String, Integer> itemLimits,
                            Map<String, Integer> groupLimits, Cooking cooking, Compacting compacting,
                            Magnet magnet, Feeding feeding, AreaWork alchemy, AreaWork refill,
                            Stack stack, Tank tank, Battery battery, Pump pump, Experience experience,
                            Jukebox jukebox, boolean allowAlwaysVoid) {
    public static final int MAX_FILTERS = 64;
    public static final int MAX_AUXILIARY = 16;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public UpgradeConfig {
        filters = Map.copyOf(filters);
        itemLimits = Map.copyOf(itemLimits);
        groupLimits = Map.copyOf(groupLimits);
        exactKeys(filters, Arrays.stream(UpgradeKind.values()).filter(UpgradeConfig::ordinaryFilters)
                .map(UpgradeKind::id).collect(Collectors.toSet()), "filter layouts");
        exactKeys(itemLimits, Arrays.stream(UpgradeKind.values()).map(UpgradeKind::id).collect(Collectors.toSet()), "item limits");
        exactKeys(groupLimits, Arrays.stream(UpgradeKind.values()).map(UpgradeKind::family).collect(Collectors.toSet()), "group limits");
        itemLimits.values().forEach(limit -> range(limit, 0, 10, "item limit"));
        groupLimits.values().forEach(limit -> range(limit, 0, 10, "group limit"));
        Objects.requireNonNull(cooking, "cooking"); Objects.requireNonNull(compacting, "compacting");
        Objects.requireNonNull(magnet, "magnet"); Objects.requireNonNull(feeding, "feeding");
        Objects.requireNonNull(alchemy, "alchemy"); Objects.requireNonNull(refill, "refill");
        Objects.requireNonNull(stack, "stack"); Objects.requireNonNull(tank, "tank");
        Objects.requireNonNull(battery, "battery"); Objects.requireNonNull(pump, "pump");
        Objects.requireNonNull(experience, "experience"); Objects.requireNonNull(jukebox, "jukebox");
    }

    private static boolean ordinaryFilters(UpgradeKind kind) {
        return kind.filterSlots() > 0 && !kind.family().equals("cooking");
    }
    public int filterSlots(UpgradeKind kind) {
        return kind.family().equals("cooking") && kind.filterSlots() > 0
                ? cooking.inputFilters() + cooking.fuelFilters()
                : ordinaryFilters(kind) ? filters.get(kind.id()).slots() : 0;
    }
    public int filterColumns(UpgradeKind kind) {
        return kind.family().equals("cooking") ? cooking.filterColumns()
                : ordinaryFilters(kind) ? filters.get(kind.id()).columns() : 4;
    }
    public int inventorySlots(UpgradeKind kind) {
        return kind == UpgradeKind.ADVANCED_JUKEBOX ? jukebox.size() : kind.inventorySlots();
    }
    public int itemLimit(UpgradeKind kind) {
        int configured = itemLimits.get(kind.id());
        return kind == UpgradeKind.TANK ? Math.min(2, configured) : kind == UpgradeKind.BATTERY ? Math.min(1, configured) : configured;
    }
    public int groupLimit(UpgradeKind kind) { return groupLimits.get(kind.family()); }

    public static UpgradeConfig defaults() {
        Map<String, Filter> filters = new LinkedHashMap<>();
        Map<String, Integer> itemLimits = new LinkedHashMap<>();
        Map<String, Integer> groupLimits = new LinkedHashMap<>();
        for (UpgradeKind kind : UpgradeKind.values()) {
            if (ordinaryFilters(kind)) filters.put(kind.id(), new Filter(kind.filterSlots(), kind.advanced() ? 4 : 3));
            itemLimits.put(kind.id(), kind.slotLimit());
            groupLimits.merge(kind.family(), kind.slotLimit(), Math::max);
        }
        return new UpgradeConfig(filters, itemLimits, groupLimits, Cooking.defaults(), Compacting.defaults(),
                new Magnet(3, 5, 10, 40), new Feeding(3, 100, 10), new AreaWork(3, 5), new AreaWork(3, 5),
                Stack.defaults(), new Tank(4_000, 1, 20, 1_000, 20), new Battery(10_000, 1, 20),
                new Pump(3, 4, 3, 20, 40, 60), new Experience(3, 5, 50, true, 5), new Jukebox(12, 4), true);
    }

    public record Filter(int slots, int columns) {
        public Filter { range(slots, 1, MAX_FILTERS, "filter slots"); range(columns, 1, 6, "filter columns"); }
    }
    public record Cooking(double speed, double fuelEfficiency, int inputFilters, int fuelFilters,
                          int filterColumns, int retryMinimum, int retryMaximum, int idleTicks) {
        public Cooking {
            decimal(speed, .25, 4, "cooking speed"); decimal(fuelEfficiency, .25, 4, "fuel efficiency");
            range(inputFilters, 1, MAX_FILTERS / 2, "cooking input filters");
            range(fuelFilters, 1, MAX_FILTERS / 2, "cooking fuel filters");
            range(filterColumns, 1, 6, "cooking filter columns");
            range(retryMinimum, 1, 1_200, "minimum cooking retry");
            range(retryMaximum, retryMinimum, 1_200, "maximum cooking retry");
            range(idleTicks, 1, 1_200, "cooking idle ticks");
        }
        public static Cooking defaults() { return new Cooking(1, 1, 8, 4, 4, 10, 60, 10); }
    }
    public record Shape(int width, int height, String pattern) {
        public Shape {
            range(width, 1, 3, "compacting shape width"); range(height, 1, 3, "compacting shape height");
            if (pattern == null || pattern.length() != width * height || !pattern.matches("[01]+")
                    || pattern.chars().filter(cell -> cell == '1').count() < 2) {
                throw new IllegalArgumentException("Compacting shapes need a binary rectangular pattern with at least two ingredients");
            }
        }
    }
    public record Compacting(int interval, int maximumOperations, List<Shape> extraShapes,
                             Map<String, List<Shape>> itemOverrides) {
        public Compacting {
            range(interval, 1, 1_200, "compacting interval"); range(maximumOperations, 1, 256, "compacting operations");
            extraShapes = shapes(extraShapes);
            Map<String, List<Shape>> copied = new LinkedHashMap<>();
            itemOverrides.forEach((item, entries) -> { identifier(item, "compacting override item"); copied.put(item, shapes(entries)); });
            if (copied.size() > 1_024) throw new IllegalArgumentException("Too many compacting item overrides");
            itemOverrides = Map.copyOf(copied);
        }
        private static List<Shape> shapes(List<Shape> values) {
            if (values.size() > 64) throw new IllegalArgumentException("Too many compacting shapes");
            return List.copyOf(values);
        }
        public static Compacting defaults() { return new Compacting(5, 64, List.of(new Shape(3, 3, "111101111")), Map.of()); }
    }
    public record Magnet(int range, int advancedRange, int activeTicks, int idleTicks) {
        public Magnet {
            UpgradeConfig.range(range, 1, 32, "magnet range"); UpgradeConfig.range(advancedRange, 1, 32, "advanced magnet range");
            ticks(activeTicks, "active magnet ticks"); ticks(idleTicks, "idle magnet ticks");
        }
        public int radius(UpgradeKind kind) { return kind.advanced() ? advancedRange : range; }
    }
    public record Feeding(int range, int idleTicks, int hungryTicks) {
        public Feeding { UpgradeConfig.range(range, 1, 32, "feeding range"); ticks(idleTicks, "feeding idle ticks"); ticks(hungryTicks, "hungry feeding ticks"); }
    }
    public record AreaWork(int range, int interval) {
        public AreaWork { UpgradeConfig.range(range, 1, 32, "upgrade range"); ticks(interval, "upgrade interval"); }
    }
    public record Stack(double baseMultiplier, Map<String, Double> multipliers, Set<String> excludedItems) {
        public Stack {
            decimal(baseMultiplier, 1d / 64, 64, "base stack multiplier");
            multipliers = Map.copyOf(multipliers);
            exactKeys(multipliers, Arrays.stream(UpgradeKind.values()).filter(kind -> kind.family().equals("stack"))
                    .map(UpgradeKind::id).collect(Collectors.toSet()), "stack multipliers");
            multipliers.values().forEach(value -> decimal(value, 1d / 64, Integer.MAX_VALUE, "stack multiplier"));
            excludedItems = Set.copyOf(excludedItems);
            excludedItems.forEach(rule -> identifier(rule.startsWith("#") ? rule.substring(1) : rule, "stack exclusion"));
        }
        public double multiplier(Collection<UpgradeKind> installed) {
            BigDecimal value = BigDecimal.valueOf(baseMultiplier);
            for (UpgradeKind kind : installed) if (kind.family().equals("stack")) {
                value = value.multiply(BigDecimal.valueOf(multipliers.get(kind.id())), MathContext.DECIMAL128);
            }
            return Math.clamp(value.doubleValue(), Double.MIN_VALUE, Double.MAX_VALUE);
        }
        public static Stack defaults() {
            Map<String, Double> factors = new LinkedHashMap<>();
            for (UpgradeKind kind : UpgradeKind.values()) if (kind.family().equals("stack")) factors.put(kind.id(), kind.stackMultiplier());
            return new Stack(1, factors, Set.of());
        }
    }
    public record Tank(int capacityPerRow, double stackRatio, int transferPerRow,
                       int minimumTransfer, int containerTicks) {
        public Tank {
            range(capacityPerRow, 500, 20_000, "tank capacity per row"); decimal(stackRatio, 0, 1, "tank stack ratio");
            range(transferPerRow, 1, 20_000, "tank transfer per row"); range(minimumTransfer, 1, 20_000, "tank minimum transfer");
            ticks(containerTicks, "tank container ticks");
        }
        public int capacity(int rows, double multiplier) { return (int) StackCapacity.resourceCapacity(rows, capacityPerRow, multiplier, stackRatio, Integer.MAX_VALUE); }
        public int transfer(int rows, double multiplier) { return Math.max(minimumTransfer, (int) StackCapacity.resourceCapacity(rows, transferPerRow, multiplier, stackRatio, Integer.MAX_VALUE)); }
    }
    public record Battery(int capacityPerRow, double stackRatio, int transferPerRow) {
        public Battery {
            range(capacityPerRow, 500, 50_000, "battery capacity per row"); decimal(stackRatio, 0, 1, "battery stack ratio");
            range(transferPerRow, 1, 50_000, "battery transfer per row");
        }
        public int capacity(int rows, double multiplier) { return (int) StackCapacity.resourceCapacity(rows, capacityPerRow, multiplier, stackRatio, Integer.MAX_VALUE); }
        public int transfer(int rows, double multiplier) { return (int) StackCapacity.resourceCapacity(rows, transferPerRow, multiplier, stackRatio, Integer.MAX_VALUE); }
    }
    public record Pump(int playerRange, int worldRange, int handTicks, int handlerTicks, int idleTicks, int handGraceTicks) {
        public Pump {
            range(playerRange, 1, 32, "pump player range"); range(worldRange, 1, 16, "pump world range");
            ticks(handTicks, "pump hand ticks"); ticks(handlerTicks, "pump handler ticks");
            ticks(idleTicks, "pump idle ticks"); range(handGraceTicks, 0, 1_200, "pump hand grace ticks");
        }
    }
    public record Experience(int range, int interval, int transferPoints, boolean allowMending, int mendingPoints) {
        public Experience {
            UpgradeConfig.range(range, 1, 32, "experience range"); ticks(interval, "experience interval");
            UpgradeConfig.range(transferPoints, 1, 10_000, "experience transfer points"); UpgradeConfig.range(mendingPoints, 1, 20, "mending points");
        }
    }
    public record Jukebox(int size, int rowWidth) {
        public Jukebox { range(size, 1, MAX_AUXILIARY, "advanced jukebox size"); range(rowWidth, 1, 6, "jukebox row width"); }
    }

    private static void exactKeys(Map<String, ?> values, Set<String> expected, String field) {
        if (!values.keySet().equals(expected)) throw new IllegalArgumentException(field + " must contain exactly the registered upgrade keys");
    }
    private static void identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + field + ": " + value);
    }
    private static void ticks(int value, String field) { range(value, 1, 1_200, field); }
    private static void range(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be in " + minimum + ".." + maximum);
    }
    private static void decimal(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(field + " must be in " + minimum + ".." + maximum);
    }
}

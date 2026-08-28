package com.kadamitas.fabricatedbackpacks.domain;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Actual item cells and existing captures both block rectangular mob space. */
public record CaptureLayout(int columns, int slots, Set<Integer> occupied) {
    public static final int MAX_SLOTS = 144;

    public CaptureLayout {
        if (columns < 1 || columns > MAX_SLOTS || slots < 0 || slots > MAX_SLOTS) {
            throw new IllegalArgumentException("Invalid backpack grid dimensions");
        }
        occupied = Set.copyOf(Objects.requireNonNull(occupied, "occupied"));
        for (int slot : occupied) Objects.checkIndex(slot, slots);
    }

    public int rows() { return Math.ceilDiv(slots, columns); }

    public record Rectangle(int x, int y, int width, int height) {
        public Rectangle {
            if (x < 0 || y < 0 || width < 1 || height < 1
                    || x > MAX_SLOTS || y > MAX_SLOTS || width > MAX_SLOTS || height > MAX_SLOTS) {
                throw new IllegalArgumentException("Invalid capture rectangle");
            }
        }

        public int area() { return width * height; }
    }

    /** Finds the first complete empty rectangle in row-major order, without wrapping rows. */
    public Optional<Rectangle> find(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Capture dimensions must be positive");
        if (width > columns || height > rows()) return Optional.empty();
        for (int y = 0; y <= rows() - height; y++) {
            for (int x = 0; x <= columns - width; x++) {
                Rectangle candidate = new Rectangle(x, y, width, height);
                if (fits(candidate) && cells(candidate).stream().noneMatch(occupied::contains)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Chooses a fitting shape close to entity proportions, penalizing wasted area. The nominal
     * cost is a minimum area, never permission to ignore blocked cells or truncate a rectangle.
     */
    public Optional<Rectangle> allocate(int nominalCost, double entityWidth, double entityHeight) {
        if (nominalCost < 1) throw new IllegalArgumentException("Capture cost must be positive");
        requirePositiveFinite(entityWidth, "entityWidth");
        requirePositiveFinite(entityHeight, "entityHeight");
        if (nominalCost > slots - occupied.size()) return Optional.empty();
        double targetLogRatio = Math.log(entityWidth) - Math.log(entityHeight);
        Rectangle best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int width = 1; width <= columns; width++) {
            for (int height = 1; height <= rows(); height++) {
                int area = width * height;
                if (area < nominalCost) continue;
                double score = Math.abs(Math.log((double) width / height) - targetLogRatio)
                        + (double) (area - nominalCost) / nominalCost;
                if (score > bestScore || (score == bestScore && best != null && area >= best.area())) continue;
                Optional<Rectangle> available = find(width, height);
                if (available.isPresent()) {
                    best = available.get();
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public CaptureLayout occupy(Rectangle rectangle) {
        Set<Integer> cells = cells(rectangle);
        if (cells.stream().anyMatch(occupied::contains)) throw new IllegalArgumentException("Capture overlaps an occupied slot");
        Set<Integer> changed = new HashSet<>(occupied);
        changed.addAll(cells);
        return new CaptureLayout(columns, slots, changed);
    }

    /** Release only a rectangle owned by the selected capture; the adapter validates that ID. */
    public CaptureLayout release(Rectangle rectangle) {
        Set<Integer> cells = cells(rectangle);
        if (!occupied.containsAll(cells)) throw new IllegalArgumentException("Capture rectangle is not fully occupied");
        Set<Integer> changed = new HashSet<>(occupied);
        changed.removeAll(cells);
        return new CaptureLayout(columns, slots, changed);
    }

    public Set<Integer> cells(Rectangle rectangle) {
        Objects.requireNonNull(rectangle, "rectangle");
        if (!fits(rectangle)) throw new IllegalArgumentException("Capture rectangle extends outside real inventory cells");
        Set<Integer> result = new HashSet<>();
        for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
            for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) result.add(y * columns + x);
        }
        return Set.copyOf(result);
    }

    private boolean fits(Rectangle rectangle) {
        return rectangle.x + rectangle.width <= columns && rectangle.y + rectangle.height <= rows()
                && (rectangle.y + rectangle.height - 1) * columns + rectangle.x + rectangle.width <= slots;
    }

    /** Cost is not clamped to an upgrade's ceiling: an oversized entity must fail eligibility. */
    public static int captureCost(double currentHealth, double effectiveMaximumHealth, boolean hostile) {
        if (!Double.isFinite(currentHealth) || currentHealth < 0) throw new IllegalArgumentException("Invalid current health");
        requirePositiveFinite(effectiveMaximumHealth, "effectiveMaximumHealth");
        double halfHealth = effectiveMaximumHealth / 2 + Math.min(currentHealth, effectiveMaximumHealth) / 2;
        double cost = Math.ceil(halfHealth * (hostile ? 2 : 1));
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, cost));
    }

    public static boolean withinUpgradeLimit(int cost, boolean hostile, boolean advanced, int maximumCost) {
        if (cost < 1 || maximumCost < 1 || maximumCost > 120) throw new IllegalArgumentException("Invalid capture limit");
        return (!hostile || advanced) && cost <= maximumCost;
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be finite and positive");
    }
}

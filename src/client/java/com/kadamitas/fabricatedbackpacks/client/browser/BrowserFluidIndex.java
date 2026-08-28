package com.kadamitas.fabricatedbackpacks.client.browser;

import com.kadamitas.fabricatedbackpacks.browser.BrowserQuery;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Small incremental companion to the shared item index; flowing/source aliases appear once. */
final class BrowserFluidIndex {
    record Entry(Identifier id, BrowserQuery.SearchText text) {}
    private final ArrayDeque<Identifier> pending = new ArrayDeque<>();
    private final List<Entry> fluids = new ArrayList<>();
    private boolean started, sorted;
    private long version;
    private String query;
    private long queryVersion = -1;
    private List<Entry> result = List.of();

    void begin(Minecraft client) {
        if (started || client.level == null) return;
        started = true;
        var unique = new TreeSet<Identifier>();
        for (var fluid : BuiltInRegistries.FLUID) if (fluid != Fluids.EMPTY) {
            FluidPresentation.canonical(BuiltInRegistries.FLUID.getKey(fluid)).ifPresent(unique::add);
        }
        pending.addAll(unique);
    }

    void tick(Minecraft client) {
        if (!started || client.level == null) return;
        long start = System.nanoTime();
        int count = 0;
        while (count < 64 && System.nanoTime() - start < 2_000_000 && !pending.isEmpty()) {
            Identifier id = pending.removeFirst();
            String name = FluidPresentation.name(id).getString();
            String tooltip = String.join(" ", FluidPresentation.tooltip(id).stream().map(line -> line.getString()).toList());
            fluids.add(new Entry(id, new BrowserQuery.SearchText(name + " " + id, id.getNamespace(), tooltip)));
            count++;
        }
        if (pending.isEmpty() && !sorted) {
            fluids.sort(Comparator.comparing(entry -> entry.text().nameAndId()));
            sorted = true;
        }
        if (count > 0) version++;
    }

    long version() { return version; }
    boolean building() { return !pending.isEmpty(); }

    List<Entry> search(String value) {
        if (value.equals(query) && queryVersion == version) return result;
        BrowserQuery parsed = BrowserQuery.parse(value);
        result = fluids.stream().filter(entry -> parsed.matches(entry.text())).toList();
        query = value;
        queryVersion = version;
        return result;
    }
}

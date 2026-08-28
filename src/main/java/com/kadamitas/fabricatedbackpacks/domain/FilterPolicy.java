package com.kadamitas.fabricatedbackpacks.domain;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Ghost matching configuration. It never extracts, consumes, or inserts an item. */
public record FilterPolicy(Mode mode, Match match, boolean matchDamage, boolean matchComponents,
                           TagMatch tagMatch, Set<String> tags, List<ItemDescriptor> entries,
                           boolean emptyAllowMatchesAll) {
    public enum Mode { ALLOW, BLOCK, CONTENTS }
    public enum Match { ITEM, NAMESPACE, TAGS }
    public enum TagMatch { ANY, ALL }

    public FilterPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(tagMatch, "tagMatch");
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        tags.forEach(ItemDescriptor::requireId);
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (mode == Mode.CONTENTS && match == Match.TAGS) {
            throw new IllegalArgumentException("Contents mode cannot use tag matching");
        }
        if (entries.stream().anyMatch(ItemDescriptor::isEmpty)) {
            throw new IllegalArgumentException("Empty ghost slots are omitted from filter entries");
        }
    }

    public FilterPolicy(Mode mode, Match match, boolean matchDamage, boolean matchComponents,
                        TagMatch tagMatch, Set<String> tags, List<ItemDescriptor> entries) {
        this(mode, match, matchDamage, matchComponents, tagMatch, tags, entries, false);
    }

    public static FilterPolicy blockList(List<ItemDescriptor> entries) {
        return new FilterPolicy(Mode.BLOCK, Match.ITEM, false, false, TagMatch.ANY, Set.of(), entries);
    }

    public static FilterPolicy allowList(List<ItemDescriptor> entries) {
        return new FilterPolicy(Mode.ALLOW, Match.ITEM, false, false, TagMatch.ANY, Set.of(), entries);
    }

    public boolean matches(ItemDescriptor candidate) {
        return matches(candidate, List.of(), List.of());
    }

    /** Memory entries still participate when their corresponding physical slots are empty. */
    public boolean matches(ItemDescriptor candidate, Collection<ItemDescriptor> contents,
                           Collection<ItemDescriptor> memory) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(memory, "memory");
        if (candidate.isEmpty()) return false;
        if (mode == Mode.CONTENTS) {
            return Stream.concat(contents.stream(), memory.stream())
                    .filter(entry -> !entry.isEmpty()).anyMatch(entry -> itemMatches(candidate, entry));
        }
        boolean selected;
        if (match == Match.TAGS) {
            selected = tagMatch == TagMatch.ALL ? candidate.tags().containsAll(tags)
                    : tags.stream().anyMatch(candidate.tags()::contains);
            // Secondary comparisons need an explicit ghost exemplar, even in tag mode.
            if (matchDamage || matchComponents) {
                selected &= entries.stream().anyMatch(entry -> secondaryMatches(candidate, entry));
            }
        } else {
            selected = entries.stream().anyMatch(entry -> itemMatches(candidate, entry));
            if (entries.isEmpty() && mode == Mode.ALLOW && emptyAllowMatchesAll) selected = true;
        }
        return mode == Mode.BLOCK ? !selected : selected;
    }

    private boolean itemMatches(ItemDescriptor candidate, ItemDescriptor entry) {
        boolean primary = match == Match.NAMESPACE ? candidate.namespace().equals(entry.namespace())
                : candidate.id().equals(entry.id());
        return primary && secondaryMatches(candidate, entry);
    }

    private boolean secondaryMatches(ItemDescriptor candidate, ItemDescriptor entry) {
        return (!matchDamage || candidate.damage() == entry.damage())
                && (!matchComponents || candidate.components().equals(entry.components()));
    }
}

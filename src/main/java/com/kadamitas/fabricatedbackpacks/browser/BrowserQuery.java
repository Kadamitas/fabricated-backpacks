package com.kadamitas.fabricatedbackpacks.browser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A small literal search language: words, @namespace, #tooltip and -exclusions. */
public record BrowserQuery(List<Term> terms) {
    public static final int MAX_LENGTH = 256;

    public BrowserQuery {
        terms = List.copyOf(terms);
        if (terms.size() > 64) throw new IllegalArgumentException("Too many browser search terms");
    }

    public static BrowserQuery parse(String input) {
        if (input.length() > MAX_LENGTH) throw new IllegalArgumentException("Browser query is too long");
        List<Term> terms = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index <= input.length(); index++) {
            char character = index == input.length() ? ' ' : input.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (index == input.length() || Character.isWhitespace(character) && !quoted) {
                if (!token.isEmpty()) terms.add(Term.parse(token.toString()));
                token.setLength(0);
            } else {
                token.append(character);
            }
        }
        return new BrowserQuery(terms);
    }

    public boolean matches(SearchText text) {
        for (Term term : terms) {
            String candidate = switch (term.field) {
                case NAME -> text.nameAndId;
                case NAMESPACE -> text.namespace;
                case TOOLTIP -> text.tooltip;
            };
            if (candidate.contains(term.text) == term.excluded) return false;
        }
        return true;
    }

    public static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        StringBuilder result = new StringBuilder(decomposed.length());
        decomposed.codePoints().filter(code -> Character.getType(code) != Character.NON_SPACING_MARK).forEach(result::appendCodePoint);
        return result.toString().toLowerCase(Locale.ROOT);
    }

    public record SearchText(String nameAndId, String namespace, String tooltip) {
        public SearchText {
            nameAndId = normalize(nameAndId);
            namespace = normalize(namespace);
            tooltip = normalize(tooltip);
        }
    }

    public enum Field { NAME, NAMESPACE, TOOLTIP }

    public record Term(Field field, boolean excluded, String text) {
        public Term {
            if (field == null || text == null) throw new IllegalArgumentException("Incomplete browser search term");
            text = normalize(text);
        }
        static Term parse(String token) {
            boolean excluded = token.startsWith("-") && token.length() > 1;
            String value = excluded ? token.substring(1) : token;
            Field field = value.startsWith("@") ? Field.NAMESPACE : value.startsWith("#") ? Field.TOOLTIP : Field.NAME;
            return new Term(field, excluded, field == Field.NAME ? value : value.substring(1));
        }
    }
}

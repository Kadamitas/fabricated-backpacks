package com.kadamitas.fabricatedbackpacks.admin;

import java.util.UUID;
import java.util.regex.Pattern;

/** Local template names are also file stems; rejecting paths keeps export resolution simple. */
public final class AdminNames {
    private static final Pattern LOCAL = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private AdminNames() { }

    public static boolean isLocal(String name) { return name != null && LOCAL.matcher(name).matches(); }
    public static String local(String name) {
        if (!isLocal(name)) throw new IllegalArgumentException("Use 1–64 lowercase letters, digits, underscores or hyphens, beginning with a letter or digit");
        return name;
    }
    public static boolean isIdentity(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}

package com.kadamitas.fabricatedbackpacks.dev;

/** Adapts Loom's development launch settings before Quilt initializes. */
public final class QuiltDevelopmentLauncher {
    private QuiltDevelopmentLauncher() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        // DLI has already read launch.cfg. Translate here, rather than in a
        // Gradle doFirst action, so IDE and separate-JVM multiplayer runs use
        // exactly the same Quilt development settings as Gradle game tests.
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("fabric.") && !name.startsWith("fabric.dli.")) {
                System.setProperty("loader." + name.substring("fabric.".length()),
                        System.getProperty(name));
            }
        }
        // DLI consumes/clears fabric.dli.env before invoking us.
        String environment = System.getProperty("fabricated.backpacks.quiltEnvironment");
        if (!"client".equals(environment) && !"server".equals(environment)) {
            throw new IllegalStateException("Missing or invalid Loom launch environment: " + environment);
        }
        String entrypoint = "org.quiltmc.loader.impl.launch.knot.Knot"
                + (environment.equals("client") ? "Client" : "Server");
        Class.forName(entrypoint).getMethod("main", String[].class).invoke(null, (Object) args);
    }
}

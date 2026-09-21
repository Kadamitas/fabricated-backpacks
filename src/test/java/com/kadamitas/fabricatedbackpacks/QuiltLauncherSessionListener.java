package com.kadamitas.fabricatedbackpacks;

import net.fabricmc.api.EnvType;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.quiltmc.loader.impl.launch.knot.Knot;

/** Runs the unchanged unit fixtures through Quilt's transformed class loader. */
public final class QuiltLauncherSessionListener implements LauncherSessionListener {
    private final ClassLoader quiltClasses;
    private ClassLoader previousClasses;

    public QuiltLauncherSessionListener() {
        System.setProperty("loader.development", "true");
        System.setProperty("loader.unitTest", "true");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            String gameDirectory = System.getProperty("fabricated.backpacks.quiltUnitGameDir");
            if (gameDirectory == null || gameDirectory.isBlank()) {
                throw new IllegalStateException("Missing isolated Quilt unit-test game directory");
            }
            quiltClasses = new Knot(EnvType.CLIENT).init(new String[] {"--gameDir", gameDirectory});
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        previousClasses = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(quiltClasses);
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        Thread.currentThread().setContextClassLoader(previousClasses);
    }
}

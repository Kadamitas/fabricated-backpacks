package com.kadamitas.fabricatedbackpacks.testplatform.impl.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Restores the embedded server's real file-backed configs after its host receives them over TCP.
 *
 * <p>Forge keeps one {@link ModConfig} per server config in the JVM. The host client joins the
 * embedded server over a real socket, so Forge's ordinary client-side config sync replaces that
 * shared config's data with a pathless in-memory copy. A later independent guest then fails
 * Forge's own {@code forge:sync_configs} task, which reads each server config from its file.
 * This test-only helper reopens the exact files through Forge's public lifecycle API.
 */
public final class EmbeddedServerConfigIsolation {
	private static final LevelResource SERVERCONFIG = new LevelResource("serverconfig");

	private EmbeddedServerConfigIsolation() {}

	public static Snapshot capture(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("Capture server configs on its own thread");
		List<Binding> bindings = ConfigTracker.configSets().get(ModConfig.Type.SERVER).stream().map(config ->
				new Binding(config, Objects.requireNonNull(config.getFullPath(), "Server config must be file-backed"),
						Objects.requireNonNull(config.getConfigData(), "Server config must be loaded"))).toList();
		return new Snapshot(server, bindings);
	}

	public static final class Snapshot {
		private final MinecraftServer server;
		private final List<Binding> bindings;

		private Snapshot(MinecraftServer server, List<Binding> bindings) {
			this.server = server;
			this.bindings = bindings;
		}

		public void restore() {
			if (!server.isSameThread()) throw new IllegalStateException("Restore server configs on its own thread");
			for (Binding binding : bindings) binding.checkValues();
			// Same call Forge's ServerLifecycleHooks makes at server start: every tracked server
			// config is reopened from its file, so the guest receives the ordinary native sync.
			ConfigTracker.loadConfigs(ModConfig.Type.SERVER, server.getWorldPath(SERVERCONFIG));
			for (Binding binding : bindings) {
				if (!binding.path().equals(binding.config().getFullPath()))
					throw new AssertionError("Embedded server config path changed: " + binding.config().getFileName());
				binding.checkValues();
			}
		}
	}

	private record Binding(ModConfig config, Path path, CommentedConfig original) {
		void checkValues() {
			CommentedConfig current = config.getConfigData();
			if (current == null || !original.valueMap().equals(current.valueMap()))
				throw new AssertionError("Embedded server config values changed: " + config.getFileName());
		}
	}
}

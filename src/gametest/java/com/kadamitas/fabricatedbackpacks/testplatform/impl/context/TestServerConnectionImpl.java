/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kadamitas.fabricatedbackpacks.testplatform.impl.context;

import java.util.Objects;
import java.util.UUID;

import com.google.common.base.Preconditions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.ClientGameTestContext;
import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.TestServerConnection;
import com.kadamitas.fabricatedbackpacks.platform.network.ClientPlayNetworking;
import com.kadamitas.fabricatedbackpacks.platform.network.ServerPlayNetworking;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.threading.ThreadingImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.util.GameTestSyncPayload;
import com.kadamitas.fabricatedbackpacks.testplatform.mixin.ClientChunkCacheAccessor;
import com.kadamitas.fabricatedbackpacks.testplatform.mixin.ClientChunkCacheStorageAccessor;
import com.kadamitas.fabricatedbackpacks.testplatform.mixin.ClientLevelAccessor;

public class TestServerConnectionImpl implements TestServerConnection {
	protected final ClientGameTestContext context;
	private final TestServerContextImpl serverContext;

	public TestServerConnectionImpl(ClientGameTestContext context, TestServerContextImpl serverContext) {
		this.context = context;
		this.serverContext = serverContext;
	}

	@Override
	public int waitForChunksDownload(int timeout) {
		ThreadingImpl.checkOnGametestThread("waitForChunksDownload");

		return context.waitFor(TestServerConnectionImpl::areChunksLoaded, timeout);
	}

	@Override
	public int waitForChunksRender(boolean waitForDownload, int timeout) {
		ThreadingImpl.checkOnGametestThread("waitForChunksRender");

		return context.waitFor(client -> (!waitForDownload || areChunksLoaded(client)) && areChunksRendered(client), timeout);
	}

	private static boolean areChunksLoaded(Minecraft client) {
		int renderDistance = client.options.getEffectiveRenderDistance();
		ClientLevel level = Objects.requireNonNull(client.level);
		ClientChunkCache.Storage chunks = ((ClientChunkCacheAccessor) level.getChunkSource()).getStorage();
		ClientChunkCacheStorageAccessor chunksAccessor = (ClientChunkCacheStorageAccessor) (Object) chunks;
		int viewCenterX = chunksAccessor.getViewCenterX();
		int viewCenterZ = chunksAccessor.getViewCenterZ();

		for (int dz = -renderDistance; dz <= renderDistance; dz++) {
			for (int dx = -renderDistance; dx <= renderDistance; dx++) {
				if (level.getChunk(viewCenterX + dx, viewCenterZ + dz, ChunkStatus.FULL, false) == null) {
					return false;
				}
			}
		}

		return true;
	}

	private static boolean areChunksRendered(Minecraft client) {
		ClientLevel level = Objects.requireNonNull(client.level);
		return ((ClientLevelAccessor) level).getLightUpdateQueue().isEmpty() && client.levelRenderer.hasRenderedAllSections();
	}

	@Override
	public void waitForClientboundPackets() {
		ThreadingImpl.checkOnGametestThread("waitForClientboundPackets");

		serverContext.runOnServer(server -> com.kadamitas.fabricatedbackpacks.testplatform.impl.NativeClientGameTestImpl.syncToClient(getServerPlayer()));

		try {
			context.waitFor(_ -> ThreadingImpl.networkSyncReceived);
		} finally {
			ThreadingImpl.networkSyncReceived = false;
		}
	}

	@Override
	public void waitForServerboundPackets() {
		ThreadingImpl.checkOnGametestThread("waitForServerboundPackets");

		context.runOnClient(_ -> com.kadamitas.fabricatedbackpacks.testplatform.impl.NativeClientGameTestImpl.syncToServer());

		try {
			serverContext.waitFor(_ -> ThreadingImpl.networkSyncReceived);
		} finally {
			ThreadingImpl.networkSyncReceived = false;
		}
	}

	@Override
	public void waitForClientboundEntityUpdates(EntityType<?> entityType, EntityType<?>... moreEntityTypes) {
		ThreadingImpl.checkOnGametestThread("waitForClientboundEntityUpdates");
		Preconditions.checkNotNull(entityType, "entityType");
		Preconditions.checkNotNull(moreEntityTypes, "moreEntityTypes");

		for (int i = 0; i < moreEntityTypes.length; i++) {
			Preconditions.checkNotNull(moreEntityTypes[i], "moreEntityTypes[" + i + "]");
		}

		int maxUpdateInterval = Math.max(0, entityType.updateInterval());

		for (EntityType<?> et : moreEntityTypes) {
			maxUpdateInterval = Math.max(maxUpdateInterval, et.updateInterval());
		}

		context.waitTicks(maxUpdateInterval);
		waitForClientboundPackets();
	}

	@Override
	public LocalPlayer getClientPlayer() {
		ThreadingImpl.checkOnClientThread("getClientPlayer");

		return Objects.requireNonNull(Minecraft.getInstance().player, "Not in world!");
	}

	@Override
	public ClientLevel getClientLevel() {
		ThreadingImpl.checkOnClientThread("getClientLevel");

		return Objects.requireNonNull(Minecraft.getInstance().level, "Not in world!");
	}

	@Override
	public ServerPlayer getServerPlayer() {
		ThreadingImpl.checkOnServerThread("getServerPlayer", serverContext.server);

		UUID uuid = Minecraft.getInstance().getGameProfile().id();
		return Objects.requireNonNull(serverContext.server.getPlayerList().getPlayer(uuid), "No corresponding player on server!");
	}

	@Override
	public ServerLevel getServerLevel() {
		ThreadingImpl.checkOnServerThread("getServerLevel", serverContext.server);

		ClientLevel clientLevel = Objects.requireNonNull(Minecraft.getInstance().level, "Not in world!");
		return Objects.requireNonNull(serverContext.server.getLevel(clientLevel.dimension()), "No corresponding level on server!");
	}
}

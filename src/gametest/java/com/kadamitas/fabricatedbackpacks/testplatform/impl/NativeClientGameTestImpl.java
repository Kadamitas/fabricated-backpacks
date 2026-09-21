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

package com.kadamitas.fabricatedbackpacks.testplatform.impl;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.payload.PayloadConnection;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.threading.ThreadingImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.util.GameTestSyncPayload;

/** Adapted from Fabric's test harness; only the loader/packet bootstrap differs. */
public class NativeClientGameTestImpl {
	public static final String MOD_ID = "fabricated_backpacks_test_harness";
	private static Channel<CustomPacketPayload> channel;

	public static void register() {
		if (!TestSystemProperties.ENABLED || channel != null) {
			return;
		}

		// This development-only channel is independent of production registration.
		PayloadConnection<CustomPacketPayload> builder = ChannelBuilder
				.named(Identifier.fromNamespaceAndPath(MOD_ID, "sync"))
				.networkProtocolVersion(1).payloadChannel();
		StreamCodec<RegistryFriendlyByteBuf, GameTestSyncPayload> codec = StreamCodec.unit(GameTestSyncPayload.INSTANCE);
		builder.play().bidirectional().add(GameTestSyncPayload.TYPE, codec,
				(_, context) -> {
					context.enqueueWork(() -> ThreadingImpl.networkSyncReceived = true);
					context.setPacketHandled(true);
				});
		channel = builder.play().bidirectional().build();
	}

	public static void syncToClient(ServerPlayer player) {
		channel.send(GameTestSyncPayload.INSTANCE, PacketDistributor.PLAYER.with(player));
	}

	public static void syncToServer() {
		channel.send(GameTestSyncPayload.INSTANCE, PacketDistributor.SERVER.noArg());
	}
}

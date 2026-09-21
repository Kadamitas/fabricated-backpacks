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

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.threading.ThreadingImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.util.GameTestSyncPayload;

/** Adapted from Fabric's test harness; only the loader/packet bootstrap differs. */
public class NativeClientGameTestImpl {
	public static final String MOD_ID = "fabricated_backpacks_test_harness";

	public static void register(IEventBus modBus) {
		if (!TestSystemProperties.ENABLED) {
			return;
		}

		// Both directions carry the same handler; a missing client handler is a client loading error.
		modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
				.playBidirectional(GameTestSyncPayload.TYPE, GameTestSyncPayload.CODEC,
						(_, _) -> ThreadingImpl.networkSyncReceived = true,
						(_, _) -> ThreadingImpl.networkSyncReceived = true));
	}
}

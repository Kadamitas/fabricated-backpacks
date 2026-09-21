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

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.dedicated.DedicatedServer;

import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.ClientGameTestContext;
import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.TestDedicatedServerConnection;
import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.TestDedicatedServerContext;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.threading.ThreadingImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.util.ClientGameTestImpl;

public class TestDedicatedServerContextImpl extends TestServerContextImpl implements TestDedicatedServerContext {
	private final ClientGameTestContext context;

	public TestDedicatedServerContextImpl(ClientGameTestContext context, DedicatedServer server) {
		super(server);
		this.context = context;
	}

	@Override
	public TestDedicatedServerConnection connect() {
		ThreadingImpl.checkOnGametestThread("connect");
		var hostProfile = context.computeOnClient(client -> client.getGameProfile().id());
		runOnServer(value -> com.kadamitas.fabricatedbackpacks.testplatform.impl.util.EmbeddedServerTagIsolation.capture(value, hostProfile));

		context.runOnClient(client -> {
			final var serverInfo = new ServerData("localhost", getConnectionAddress(), ServerData.Type.OTHER);
			ConnectScreen.startConnecting(client.gui.screen(), client, ServerAddress.parseString(getConnectionAddress()), serverInfo, false, null);
		});

		ClientGameTestImpl.waitForWorldLoad(context);

		return new TestDedicatedServerConnectionImpl(context, this);
	}

	private String getConnectionAddress() {
		return "localhost:" + server.getPort();
	}

	@Override
	public void close() {
		ThreadingImpl.checkOnGametestThread("close");

		if (!ThreadingImpl.isServerRunning || !server.getRunningThread().isAlive()) {
			throw new AssertionError("Stopped the dedicated server before closing the dedicated server context");
		}

		com.kadamitas.fabricatedbackpacks.testplatform.impl.util.EmbeddedServerTagIsolation.clear(server);
		server.halt(false);
		context.waitFor(client -> !ThreadingImpl.isServerRunning && !server.getRunningThread().isAlive());
	}
}

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

package com.kadamitas.fabricatedbackpacks.testplatform.impl.world;

import java.nio.file.Path;

import com.google.common.base.Preconditions;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.ClientGameTestContext;
import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.TestSingleplayerContext;
import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.world.TestWorldSave;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.context.TestSingleplayerContextImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.threading.ThreadingImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.util.ClientGameTestImpl;

public final class TestWorldSaveImpl implements TestWorldSave {
	private final ClientGameTestContext context;
	private final Path saveDirectory;

	public TestWorldSaveImpl(ClientGameTestContext context, Path saveDirectory) {
		this.context = context;
		this.saveDirectory = saveDirectory;
	}

	@Override
	public Path getSaveDirectory() {
		return saveDirectory;
	}

	@Override
	public TestSingleplayerContext open() {
		ThreadingImpl.checkOnGametestThread("open");
		Preconditions.checkState(!ThreadingImpl.isServerRunning, "Cannot open a world when a server is running");

		context.runOnClient(client -> {
			client.createWorldOpenFlows().openWorld(saveDirectory.getFileName().toString(), () -> {
				throw new AssertionError("World opening should not be canceled");
			});
		});

		ClientGameTestImpl.waitForWorldLoad(context);

		MinecraftServer server = context.computeOnClient(Minecraft::getSingleplayerServer);
		return new TestSingleplayerContextImpl(context, this, server);
	}
}

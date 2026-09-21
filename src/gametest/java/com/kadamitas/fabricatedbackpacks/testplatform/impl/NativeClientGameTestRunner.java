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

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.NativeClientGameTest;
import com.kadamitas.fabricatedbackpacks.testplatform.api.v1.context.ClientGameTestContext;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.context.ClientGameTestContextImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.threading.ThreadingImpl;
import com.kadamitas.fabricatedbackpacks.testplatform.impl.util.WindowHooks;

public class NativeClientGameTestRunner {
	public static NativeClientGameTest currentlyRunningGameTest = null;

	public static void start() {
		ThreadingImpl.unsafeClientInstance = Minecraft.getInstance();
		// make the game think the window is focused
		((WindowHooks) (Object) Minecraft.getInstance().getWindow()).fabric_focus();

		List<NativeClientGameTest> gameTests = getTestToRun();

		ThreadingImpl.runTestThread(() -> {
			ClientGameTestContextImpl context = new ClientGameTestContextImpl();

			for (NativeClientGameTest gameTest : gameTests) {
				currentlyRunningGameTest = gameTest;

				try {
					setupInitialGameTestState(context);
					gameTest.runTest(context);
					setupAndCheckFinalGameTestState(context);
				} finally {
					currentlyRunningGameTest = null;
				}
			}
		});
	}

	private static List<NativeClientGameTest> getTestToRun() {
		// Explicit native test-mod entrypoint, never Fabric Loader discovery.
		List<NativeClientGameTest> gameTests = List.of(new com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests());
		String filter = System.getProperty(TestSystemProperties.MOD_ID_FILTER_PROPERTY);

		if (filter == null) {
			return gameTests;
		}

		List<String> modIds = Arrays.stream(filter.split(","))
				.map(String::trim)
				.filter(modId -> !modId.isEmpty())
				.toList();

		if (modIds.isEmpty()) {
			throw new IllegalArgumentException("No valid mod IDs specified in the client game test filter");
		}

		if (!modIds.equals(List.of("fabricated_backpacks_tests"))) {
			throw new IllegalArgumentException("No tests found for the specified mod IDs: " + modIds);
		}

		return gameTests;
	}

	private static void setupInitialGameTestState(ClientGameTestContext context) {
		context.restoreDefaultGameOptions();
	}

	private static void setupAndCheckFinalGameTestState(ClientGameTestContextImpl context) {
		context.getInput().clearKeysDown();
		context.runOnClient(client -> ((WindowHooks) (Object) client.getWindow()).fabric_resetSize());
		context.getInput().setCursorPos(context.computeOnClient(client -> client.getWindow().getScreenWidth()) * 0.5, context.computeOnClient(client -> client.getWindow().getScreenHeight()) * 0.5);

		if (ThreadingImpl.isServerRunning) {
			throw new AssertionError("Client gametest %s finished while a server is still running".formatted(currentlyRunningGameTest.getClass().getName()));
		}

		context.runOnClient(client -> {
			if (client.level != null) {
				throw new AssertionError("Client gametest %s finished while still connected to a server".formatted(currentlyRunningGameTest.getClass().getName()));
			}

			if (!(client.gui.screen() instanceof TitleScreen)) {
				throw new AssertionError("Client gametest %s did not finish on the title screen. Current screen %s".formatted(currentlyRunningGameTest.getClass().getName(), client.gui.screen()));
			}
		});
	}
}

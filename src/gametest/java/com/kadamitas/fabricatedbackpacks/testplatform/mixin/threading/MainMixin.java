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

package com.kadamitas.fabricatedbackpacks.testplatform.mixin.threading;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.server.Main;

@Mixin(Main.class)
public class MainMixin {
	@WrapWithCondition(method = "main", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/server/loading/ServerModLoader;load(Z)V"))
	private static boolean dontConstructLoadedClientModsAgain(boolean gameTestServer) {
		// The upstream harness starts a real dedicated server in its host client JVM.
		// NeoForge's client already completed mod construction/registration; starting
		// the server must reuse that runtime instead of registering every mod twice.
		return com.kadamitas.fabricatedbackpacks.testplatform.impl.util.DedicatedServerImplUtil.serverFuture == null
				|| !net.neoforged.fml.loading.FMLEnvironment.getDist().isClient();
	}

	@WrapWithCondition(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;startTimerHackThread()V"))
	private static boolean dontStartAnotherTimerHack() {
		return false;
	}
}

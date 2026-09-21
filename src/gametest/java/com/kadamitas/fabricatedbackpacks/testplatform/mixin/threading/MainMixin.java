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
	@WrapWithCondition(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/server/loading/ServerModLoader;load()V"))
	private static boolean doNotReloadInitializedClientMods() {
		// The native client already loaded the production and test mods. Starting a real dedicated
		// server for this harness must not construct those same mods a second time in the same JVM.
		return com.kadamitas.fabricatedbackpacks.testplatform.impl.util.DedicatedServerImplUtil.serverFuture == null
				|| net.minecraftforge.fml.loading.FMLEnvironment.dist != net.minecraftforge.api.distmarker.Dist.CLIENT;
	}

	@WrapWithCondition(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;startTimerHackThread()V"))
	private static boolean dontStartAnotherTimerHack() {
		return false;
	}
}

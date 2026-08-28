package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.gametest.compat.GameTestInfoAccess;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/** Uses structure-content coordinates, with floor at local y=0 on both targets. */
abstract class ContentGameTests implements FabricGameTest {
    @Override public final void invokeTestMethod(GameTestHelper helper, Method method) {
        // 1.21.1 anchors its helper at the structure block one block below the
        // content. Keep the native test info, clock, assertions and world; only
        // translate fixture coordinates. No runtime collision/permission bypass.
        GameTestInfo info = ((GameTestInfoAccess) helper).fabricatedBackpacks$testInfo();
        FabricGameTest.super.invokeTestMethod(new ContentHelper(info), method);
    }

    private static final class ContentHelper extends GameTestHelper {
        private ContentHelper(GameTestInfo info) { super(info); }
        @Override public BlockPos absolutePos(BlockPos relative) { return super.absolutePos(relative.above()); }
        @Override public Vec3 absoluteVec(Vec3 relative) { return super.absoluteVec(relative.add(0, 1, 0)); }
        @Override public BlockPos relativePos(BlockPos absolute) { return super.relativePos(absolute).below(); }
        @Override public Vec3 relativeVec(Vec3 absolute) { return super.relativeVec(absolute).add(0, -1, 0); }
    }
}

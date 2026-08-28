package com.kadamitas.fabricatedbackpacks.automation.conduit;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConduitFilterProtocolTest {
    private static final ResourceLocation STONE = ResourceLocation.withDefaultNamespace("stone");
    private static final ResourceLocation WATER = ResourceLocation.withDefaultNamespace("water");

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static Stream<ConduitFilterAction> actions() {
        return Stream.of(ConduitKind.ITEM, ConduitKind.FLUID).flatMap(kind -> Stream.of(
                new ConduitFilterAction(17, kind, ConduitFilterAction.Operation.SET_MODE, 2, Optional.empty()),
                new ConduitFilterAction(17, kind, ConduitFilterAction.Operation.SET_ENTRY, 8,
                        Optional.of(kind == ConduitKind.ITEM ? STONE : WATER)),
                new ConduitFilterAction(17, kind, ConduitFilterAction.Operation.CLEAR_ENTRY, 0, Optional.empty())));
    }

    @ParameterizedTest
    @MethodSource("actions")
    void everyMutationCrossesActualBytesWithoutRegistryNumericIds(ConduitFilterAction action) {
        assertEquals(action, roundTrip(ConduitFilterAction.STREAM_CODEC, action));
    }

    @Test
    void aViewerSnapshotPreservesTheFixedFaceAndSparsePolicies() {
        var item = new ConduitFilter(ConduitFilterMode.ALLOW, Map.of(0, STONE, 8, ResourceLocation.parse("removed_mod:part")));
        var fluid = new ConduitFilter(ConduitFilterMode.BLOCK, Map.of(4, WATER));
        var snapshot = new ConduitFilterState(17, Direction.SOUTH, item, fluid);
        assertEquals(snapshot, roundTrip(ConduitFilterState.STREAM_CODEC, snapshot));
        assertEquals(ConduitFilter.EMPTY, roundTrip(ConduitFilter.STREAM_CODEC, ConduitFilter.EMPTY));
    }

    @Test
    void requestConstructionRejectsEnergyWrongRowsAndUnexpectedIdentifierFields() {
        assertThrows(IllegalArgumentException.class, () -> new ConduitFilterAction(17, ConduitKind.ENERGY,
                ConduitFilterAction.Operation.SET_MODE, 0, Optional.empty()));
        for (int row : new int[]{-1, 9, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new ConduitFilterAction(17, ConduitKind.ITEM,
                    ConduitFilterAction.Operation.SET_ENTRY, row, Optional.of(STONE)));
        assertThrows(IllegalArgumentException.class, () -> new ConduitFilterAction(-1, ConduitKind.ITEM,
                ConduitFilterAction.Operation.SET_MODE, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ConduitFilterAction(17, ConduitKind.ITEM,
                ConduitFilterAction.Operation.SET_MODE, 3, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ConduitFilterAction(17, ConduitKind.ITEM,
                ConduitFilterAction.Operation.SET_ENTRY, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ConduitFilterAction(17, ConduitKind.FLUID,
                ConduitFilterAction.Operation.CLEAR_ENTRY, 0, Optional.of(WATER)));
        assertThrows(IllegalArgumentException.class, () -> new ConduitFilterAction(17, ConduitKind.ITEM,
                ConduitFilterAction.Operation.SET_MODE, 0, Optional.of(STONE)));
    }

    @Test
    void invalidWireKindsOperationsAndFacesAreNotWrappedIntoValidEnums() {
        for (int kind : new int[]{-1, 2, Integer.MAX_VALUE}) {
            var buffer = buffer();
            try {
                buffer.writeVarInt(17).writeVarInt(kind);
                assertThrows(DecoderException.class, () -> ConduitFilterAction.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
        var operation = buffer();
        try {
            operation.writeVarInt(17).writeVarInt(0).writeVarInt(3);
            assertThrows(DecoderException.class, () -> ConduitFilterAction.STREAM_CODEC.decode(operation));
        } finally { operation.release(); }
        var face = buffer();
        try {
            face.writeVarInt(17).writeVarInt(6);
            assertThrows(DecoderException.class, () -> ConduitFilterState.STREAM_CODEC.decode(face));
        } finally { face.release(); }
    }

    @Test
    void wireBoundsRejectOversizedCountsRowsDuplicatesAndIdentifierLengths() {
        var count = buffer();
        try {
            count.writeByte(0).writeByte(255);
            assertThrows(DecoderException.class, () -> ConduitFilter.STREAM_CODEC.decode(count));
            assertEquals(2, count.readerIndex(), "Reject the count before reading or allocating entries");
        } finally { count.release(); }
        var row = buffer();
        try {
            row.writeByte(1).writeByte(1).writeByte(9);
            assertThrows(DecoderException.class, () -> ConduitFilter.STREAM_CODEC.decode(row));
        } finally { row.release(); }
        var duplicate = buffer();
        try {
            duplicate.writeByte(1).writeByte(2).writeByte(0);
            duplicate.writeUtf(STONE.toString());
            duplicate.writeByte(8);
            duplicate.writeUtf(STONE.toString());
            assertThrows(DecoderException.class, () -> ConduitFilter.STREAM_CODEC.decode(duplicate));
        } finally { duplicate.release(); }
        var oversized = buffer();
        try {
            oversized.writeVarInt(17).writeVarInt(0).writeVarInt(1).writeVarInt(0).writeBoolean(true);
            oversized.writeUtf("m:" + "x".repeat(255));
            assertThrows(DecoderException.class, () -> ConduitFilterAction.STREAM_CODEC.decode(oversized));
        } finally { oversized.release(); }
    }

    @Test
    void aTruncatedViewerSnapshotCannotBecomeAnEmptyUnrestrictedPolicy() {
        var expected = new ConduitFilterState(17, Direction.EAST, ConduitFilter.DENY_ALL,
                ConduitFilter.EMPTY.withEntry(8, WATER));
        var buffer = buffer();
        try {
            ConduitFilterState.STREAM_CODEC.encode(buffer, expected);
            buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(RuntimeException.class, () -> ConduitFilterState.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        var buffer = buffer();
        try {
            codec.encode(buffer, value);
            assertTrue(buffer.readableBytes() > 0);
            T decoded = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally { buffer.release(); }
    }
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
}

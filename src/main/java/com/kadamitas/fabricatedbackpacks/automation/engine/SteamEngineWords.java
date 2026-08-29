package com.kadamitas.fabricatedbackpacks.automation.engine;

/** Vanilla container data packets carry signed shorts, even though ContainerData exposes ints. */
final class SteamEngineWords {
    private SteamEngineWords() { }
    static int word(long value, int word) {
        if (word < 0 || word > 3) throw new IllegalArgumentException("Invalid data word");
        return (int) (value >>> (word * 16)) & 0xffff;
    }
    static long join(int low, int next, int nextHigh, int high) {
        return (low & 0xffffL) | (next & 0xffffL) << 16 | (nextHigh & 0xffffL) << 32 | (high & 0xffffL) << 48;
    }
}

package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.kadamitas.fabricatedbackpacks.platform.neoforge.LongResourceStorage;
import com.kadamitas.fabricatedbackpacks.platform.neoforge.NativeResourceHandler;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Bidirectional native capability boundary. Both directions share NeoForge transactions. */
public class NativeStorage<T extends TransferVariant<?>, R extends Resource> implements SlottedStorage<T> {
    protected final ResourceHandler<R> handler;
    private final Function<R, T> wrap;
    private final Function<T, R> unwrap;
    private final long units;
    public NativeStorage(ResourceHandler<R> handler, Function<R, T> wrap, Function<T, R> unwrap, long units) {
        this.handler = handler; this.wrap = wrap; this.unwrap = unwrap; this.units = units;
    }
    /**
     * The mod's own exported storage comes back unchanged so that identity, long amounts,
     * sub-unit granularity and support flags survive a native capability round trip. Any other
     * handler is bridged through NeoForge's native units.
     */
    @SuppressWarnings("unchecked")
    public static <T extends TransferVariant<?>, R extends Resource> Storage<T> adopt(
            ResourceHandler<R> handler, Function<R, T> wrap, Function<T, R> unwrap, long units) {
        if (handler instanceof NativeResourceHandler<?> exported && exported.origin() instanceof Storage<?> origin) return (Storage<T>) origin;
        return new NativeStorage<>(handler, wrap, unwrap, units);
    }
    public static <T extends TransferVariant<?>, R extends Resource> ResourceHandler<R> export(
            Storage<T> storage, Function<R, T> wrap, Function<T, R> unwrap, long units) {
        if (storage == null) return null;
        return new NativeResourceHandler<>(new LongResourceStorage<R>() {
            private List<StorageView<T>> views() { var views = new ArrayList<StorageView<T>>(); storage.forEach(views::add); return views; }
            private StorageView<T> view(int slot) { return views().get(slot); }
            @Override public int size() { return views().size(); }
            @Override public R resource(int slot) { return unwrap.apply(view(slot).getResource()); }
            @Override public long amount(int slot) { return view(slot).getAmount(); }
            @Override public long capacity(int slot, R resource) { return view(slot).getCapacity(); }
            @Override public boolean accepts(int slot, R resource) { return view(slot) instanceof SingleSlotStorage<?> single && single.supportsInsertion(); }
            @Override public long insert(int slot, R resource, long amount, TransactionContext tx) {
                var view = view(slot);
                return view instanceof SingleSlotStorage<T> single ? single.insert(wrap.apply(resource), amount, tx) : 0;
            }
            @Override public long extract(int slot, R resource, long amount, TransactionContext tx) { return view(slot).extract(wrap.apply(resource), amount, tx); }
            @Override public long insert(R resource, long amount, TransactionContext tx) { return storage.insert(wrap.apply(resource), amount, tx); }
            @Override public long extract(R resource, long amount, TransactionContext tx) { return storage.extract(wrap.apply(resource), amount, tx); }
        }, units, storage);
    }
    private long stored(long nativeAmount) { return nativeAmount > Long.MAX_VALUE / units ? Long.MAX_VALUE : nativeAmount * units; }
    private int request(long maximum) { StoragePreconditions.notNegative(maximum); return (int) Math.min(Integer.MAX_VALUE, maximum / units); }
    @Override public long insert(T resource, long maximum, TransactionContext tx) { return handler.insert(unwrap.apply(resource), request(maximum), tx) * units; }
    @Override public long extract(T resource, long maximum, TransactionContext tx) { return handler.extract(unwrap.apply(resource), request(maximum), tx) * units; }
    @Override public int getSlotCount() { return handler.size(); }
    @Override public SingleSlotStorage<T> getSlot(int slot) {
        java.util.Objects.checkIndex(slot, handler.size());
        return new SingleSlotStorage<>() {
            @Override public T getResource() { return wrap.apply(handler.getResource(slot)); }
            @Override public boolean isResourceBlank() { return getResource().isBlank(); }
            @Override public long getAmount() { return stored(handler.getAmountAsLong(slot)); }
            @Override public long getCapacity() { return stored(handler.getCapacityAsLong(slot, handler.getResource(slot))); }
            @Override public long insert(T resource, long maximum, TransactionContext tx) { return handler.insert(slot, unwrap.apply(resource), request(maximum), tx) * units; }
            @Override public long extract(T resource, long maximum, TransactionContext tx) { return handler.extract(slot, unwrap.apply(resource), request(maximum), tx) * units; }
        };
    }
    @Override public Iterator<StorageView<T>> iterator() { return java.util.stream.IntStream.range(0, getSlotCount()).mapToObj(i -> (StorageView<T>) getSlot(i)).iterator(); }
}

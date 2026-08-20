package com.boydti.fawe.example;

import com.boydti.fawe.object.FaweChunk;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.RunnableVal;
import com.boydti.fawe.util.MathMan;
import com.boydti.fawe.util.SetQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DefaultFaweQueueMap implements IFaweQueueMap {

    private final MappedFaweQueue parent;

    public DefaultFaweQueueMap(MappedFaweQueue parent) {
        this.parent = parent;
    }

    public final Long2ObjectOpenHashMap<FaweChunk> blocks = new Long2ObjectOpenHashMap<FaweChunk>() {
        @Override
        public FaweChunk put(Long key, FaweChunk value) {
            return put((long) key, value);
        }

        @Override
        public FaweChunk put(long key, FaweChunk value) {
            if (parent.getProgressTask() != null) {
                try {
                    parent.getProgressTask().run(FaweQueue.ProgressType.QUEUE, size() + 1);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            synchronized (this) {
                return super.put(key, value);
            }
        }
    };

    @Override
    public Collection<FaweChunk> getFaweCunks() {
        synchronized (blocks) {
            return new HashSet<>(blocks.values());
        }
    }

    @Override
    public void forEachChunk(RunnableVal<FaweChunk> onEach) {
        synchronized (blocks) {
            for (Map.Entry<Long, FaweChunk> entry : blocks.entrySet()) {
                onEach.run(entry.getValue());
            }
        }
    }

    @Override
    public FaweChunk getFaweChunk(int cx, int cz) {
        ChunkCache cached = lastChunk;
        if (cached != null && cached.x == cx && cached.z == cz) {
            return cached.chunk;
        }
        long pair = MathMan.pairInt(cx, cz);
        FaweChunk chunk = this.blocks.get(pair);
        if (chunk == null) {
            chunk = this.getNewFaweChunk(cx, cz);
            FaweChunk previous = this.blocks.put(pair, chunk);
            if (previous != null) {
                blocks.put(pair, previous);
                chunk = previous;
            }
        }
        lastChunk = new ChunkCache(cx, cz, chunk);
        return chunk;
    }

    @Override
    public FaweChunk getCachedFaweChunk(int cx, int cz) {
        ChunkCache cached = lastChunk;
        if (cached != null && cached.x == cx && cached.z == cz) {
            return cached.chunk;
        }
        long pair = MathMan.pairInt(cx, cz);
        FaweChunk chunk = this.blocks.get(pair);
        if (chunk != null) {
            lastChunk = new ChunkCache(cx, cz, chunk);
        }
        return chunk;
    }

    @Override
    public void add(FaweChunk chunk) {
        long pair = MathMan.pairInt(chunk.getX(), chunk.getZ());
        FaweChunk previous = this.blocks.put(pair, chunk);
        if (previous != null) {
            blocks.put(pair, previous);
        }
    }


    @Override
    public void clear() {
        blocks.clear();
        lastChunk = null;
    }

    @Override
    public int size() {
        return blocks.size();
    }

    private FaweChunk getNewFaweChunk(int cx, int cz) {
        return parent.getFaweChunk(cx, cz);
    }

    private volatile ChunkCache lastChunk;

    private boolean isLastChunk(FaweChunk chunk) {
        ChunkCache cached = lastChunk;
        return cached != null && cached.chunk == chunk;
    }

    private void invalidate(FaweChunk chunk) {
        if (isLastChunk(chunk)) {
            lastChunk = null;
        }
    }

    private static final class ChunkCache {
        private final int x;
        private final int z;
        private final FaweChunk chunk;

        private ChunkCache(int x, int z, FaweChunk chunk) {
            this.x = x;
            this.z = z;
            this.chunk = chunk;
        }
    }

    @Override
    public boolean next(int amount, long time) {
        synchronized (blocks) {
            try {
                boolean skip = parent.getStage() == SetQueue.QueueStage.INACTIVE;
                int added = 0;
                Iterator<Map.Entry<Long, FaweChunk>> iter = blocks.entrySet().iterator();
                if (amount == 1) {
                    long start = System.currentTimeMillis();
                    do {
                        if (iter.hasNext()) {
                            FaweChunk chunk = iter.next().getValue();
                            if (skip && isLastChunk(chunk)) {
                                continue;
                            }
                            invalidate(chunk);
                            iter.remove();
                            parent.start(chunk);
                            chunk.call();
                            parent.end(chunk);
                        } else {
                            break;
                        }
                    } while (System.currentTimeMillis() - start < time);
                } else {
                    ExecutorCompletionService service = SetQueue.IMP.getCompleterService();
                    ForkJoinPool pool = SetQueue.IMP.getForkJoinPool();
                    boolean result = true;
                    // amount = 8;
                    for (int i = 0; i < amount && (result = iter.hasNext()); i++) {
                        Map.Entry<Long, FaweChunk> item = iter.next();
                        FaweChunk chunk = item.getValue();
                        if (skip && isLastChunk(chunk)) {
                            i--;
                            continue;
                        }
                        invalidate(chunk);
                        iter.remove();
                        parent.start(chunk);
                        service.submit(chunk);
                        added++;
                    }
                    // if result, then submitted = amount
                    if (result) {
                        long start = System.currentTimeMillis();
                        while (System.currentTimeMillis() - start < time && result) {
                            if (result = iter.hasNext()) {
                                Map.Entry<Long, FaweChunk> item = iter.next();
                                FaweChunk chunk = item.getValue();
                                if (skip && isLastChunk(chunk)) {
                                    continue;
                                }
                                invalidate(chunk);
                                iter.remove();
                                parent.start(chunk);
                                service.submit(chunk);
                                Future future = service.poll(50, TimeUnit.MILLISECONDS);
                                if (future != null) {
                                    FaweChunk fc = (FaweChunk) future.get();
                                    parent.end(fc);
                                }
                            }
                        }
                    }
                    pool.awaitQuiescence(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                    Future future;
                    while ((future = service.poll()) != null) {
                        FaweChunk fc = (FaweChunk) future.get();
                        parent.end(fc);
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return !blocks.isEmpty();
        }
    }
}

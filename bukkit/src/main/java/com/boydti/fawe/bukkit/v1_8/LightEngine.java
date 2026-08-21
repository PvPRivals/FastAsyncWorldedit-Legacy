package com.boydti.fawe.bukkit.v1_8;

import com.boydti.fawe.config.Settings;
import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.Chunk;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.IBlockData;
import net.minecraft.server.v1_8_R3.World;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RelightEngine rebuilds all light inside one chunk from scratch. It uses a
 * padded 46 by 46 by 256 temporary volume so light can enter the center chunk
 * from every block that is close enough to affect it.
 *
 * Temporary arrays are borrowed from a bounded, process-wide pool. A relight
 * only locks the spatial regions touched by its 3 by 3 input chunk grid, so
 * distant chunks (and different worlds) can be rebuilt concurrently.
 */
final class LightEngine {

    private static final int SKYLESS_RADIUS = 15;
    private static final int SKYLESS_DIAMETER = 16 + SKYLESS_RADIUS * 2;
    private static final int SKYLESS_X_STRIDE = SKYLESS_DIAMETER << 8;
    private static final int SKYLESS_Z_STRIDE = 1 << 8;
    private static final int SKYLESS_SIZE = SKYLESS_DIAMETER * 256 * SKYLESS_DIAMETER;
    private static final int SKYLESS_CHUNK_GRID = 3;
    private static final int REGION_SHIFT = 3;
    private static final int REGION_LOCK_COUNT = 1024;
    private static final int REGION_LOCK_MASK = REGION_LOCK_COUNT - 1;
    private static final int INITIAL_QUEUE_SIZE = 4096;
    private static final int MAX_PARALLEL_RELIGHTS = Math.max(1, Settings.IMP.LIGHTING.PARALLEL_THREADS);
    private static final int MAX_RETAINED_QUEUE_SIZE = Math.max(INITIAL_QUEUE_SIZE,
            Settings.IMP.LIGHTING.MAX_RETAINED_QUEUE_SIZE);
    private static final Semaphore RELIGHT_PERMITS = new Semaphore(MAX_PARALLEL_RELIGHTS);
    private static final ConcurrentLinkedQueue<RelightEngine> AVAILABLE_ENGINES = new ConcurrentLinkedQueue<>();

    private static final boolean[] SKYLESS_CAN_SPREAD_NEG_X = new boolean[SKYLESS_DIAMETER * SKYLESS_DIAMETER];
    private static final boolean[] SKYLESS_CAN_SPREAD_POS_X = new boolean[SKYLESS_DIAMETER * SKYLESS_DIAMETER];
    private static final boolean[] SKYLESS_CAN_SPREAD_NEG_Z = new boolean[SKYLESS_DIAMETER * SKYLESS_DIAMETER];
    private static final boolean[] SKYLESS_CAN_SPREAD_POS_Z = new boolean[SKYLESS_DIAMETER * SKYLESS_DIAMETER];
    private static final int[] SKYLESS_CHUNK_GRID_X = new int[SKYLESS_DIAMETER];
    private static final int[] SKYLESS_CHUNK_GRID_Z = new int[SKYLESS_DIAMETER];
    private static final int[] SKYLESS_LOCAL_X = new int[SKYLESS_DIAMETER];
    private static final int[] SKYLESS_LOCAL_Z = new int[SKYLESS_DIAMETER];
    private static final int[] SKYLESS_COLUMN_BASE = new int[SKYLESS_DIAMETER * SKYLESS_DIAMETER];
    private final ReentrantLock[] regionLocks = new ReentrantLock[REGION_LOCK_COUNT];

    // Block state lookup tables.
    // The index is the block state id stored in ChunkSection.blockIds.
    // LIGHT_OPACITY is max(1, Block.p()). Emitting full blocks use opacity 1 so their own light can leave the block.
    // LIGHT_EMISSION is Block.r(), from 0 to 15.
    private static final int STATE_COUNT = Character.MAX_VALUE + 1;
    private static final byte[] LIGHT_OPACITY = new byte[STATE_COUNT];
    private static final byte[] LIGHT_EMISSION = new byte[STATE_COUNT];

    static {
        Arrays.fill(LIGHT_OPACITY, (byte) 1);
        for (IBlockData ibd : Block.d) {
            if (ibd == null) {
                continue;
            }
            int i = Block.getCombinedId(ibd);
            int emission = 0, opacity = 1;
            Block blk = ibd.getBlock();
            emission = blk.r();
            opacity = blk.p();
            if (opacity >= 15 && emission > 0) opacity = 1; // Emitting full blocks must let their own light spread.
            if (opacity < 1) opacity = 1;
            if (i >= 0 && i < STATE_COUNT) {
                LIGHT_OPACITY[i] = (byte) opacity;
                LIGHT_EMISSION[i] = (byte) emission;
            }
        }

        for (int x = 0; x < SKYLESS_DIAMETER; ++x) {
            SKYLESS_CHUNK_GRID_X[x] = (x + 1) >> 4;
            SKYLESS_LOCAL_X[x] = (x + 1) & 15;
            for (int z = 0; z < SKYLESS_DIAMETER; ++z) {
                int column = x * SKYLESS_DIAMETER + z;
                if (x == 0) {
                    SKYLESS_CHUNK_GRID_Z[z] = (z + 1) >> 4;
                    SKYLESS_LOCAL_Z[z] = (z + 1) & 15;
                }
                SKYLESS_COLUMN_BASE[column] = column << 8;
                SKYLESS_CAN_SPREAD_NEG_X[column] = x > 0;
                SKYLESS_CAN_SPREAD_POS_X[column] = x < SKYLESS_DIAMETER - 1;
                SKYLESS_CAN_SPREAD_NEG_Z[column] = z > 0;
                SKYLESS_CAN_SPREAD_POS_Z[column] = z < SKYLESS_DIAMETER - 1;
            }
        }
    }

    LightEngine() {
        for (int i = 0; i < regionLocks.length; ++i) {
            regionLocks[i] = new ReentrantLock();
        }
    }

    boolean relightChunk(World world, Chunk center) {
        int[] lockedRegions = lockRegions(center.locX, center.locZ);
        RelightEngine engine = null;
        boolean permitAcquired = false;
        boolean reusable = false;
        try {
            RELIGHT_PERMITS.acquireUninterruptibly();
            permitAcquired = true;
            engine = AVAILABLE_ENGINES.poll();
            if (engine == null) {
                engine = new RelightEngine();
            }
            boolean result = engine.relightChunk(world, center);
            reusable = true;
            return result;
        } finally {
            if (engine != null) {
                engine.releaseReferences();
                if (reusable) {
                    AVAILABLE_ENGINES.offer(engine);
                }
            }
            if (permitAcquired) {
                RELIGHT_PERMITS.release();
            }
            unlockRegions(lockedRegions);
        }
    }

    private int[] lockRegions(int chunkX, int chunkZ) {
        int minRegionX = (chunkX - 1) >> REGION_SHIFT;
        int maxRegionX = (chunkX + 1) >> REGION_SHIFT;
        int minRegionZ = (chunkZ - 1) >> REGION_SHIFT;
        int maxRegionZ = (chunkZ + 1) >> REGION_SHIFT;
        int[] indices = new int[5];
        int count = 0;

        for (int regionX = minRegionX; regionX <= maxRegionX; ++regionX) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; ++regionZ) {
                int index = regionLockIndex(regionX, regionZ);
                boolean duplicate = false;
                for (int i = 1; i <= count; ++i) {
                    if (indices[i] == index) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    indices[++count] = index;
                }
            }
        }

        Arrays.sort(indices, 1, count + 1);
        indices[0] = count;
        for (int i = 1; i <= count; ++i) {
            regionLocks[indices[i]].lock();
        }
        return indices;
    }

    private int regionLockIndex(int regionX, int regionZ) {
        int hash = regionX * 73428767 ^ regionZ * 912931;
        hash ^= hash >>> 16;
        return hash & REGION_LOCK_MASK;
    }

    private void unlockRegions(int[] indices) {
        for (int i = indices[0]; i > 0; --i) {
            regionLocks[indices[i]].unlock();
        }
    }

    /**
     * Rebuilds complete chunk light using a padded temporary volume.
     *
     * The same queue code is used for block light and for relight sky light.
     * Block light seeds are emitting blocks. Sky light seeds are columns above
     * the height map plus border cells that can push sky light inward.
     */
    private static final class RelightEngine {

        private final byte[] skylessLight = new byte[SKYLESS_SIZE];
        private final byte[] skylessOpacity = new byte[SKYLESS_SIZE];
        private final Chunk[] skylessChunks = new Chunk[SKYLESS_CHUNK_GRID * SKYLESS_CHUNK_GRID];
        private final int[][] skylessQueues = new int[16][];
        private final int[] skylessQueueSizes = new int[16];
        private int[] skylessBlockSourceIndices = new int[4096];
        private byte[] skylessBlockSourceLevels = new byte[4096];
        private int skylessBlockSourceCount;

        private RelightEngine() {
            for (int i = 0; i < skylessQueues.length; ++i) {
                skylessQueues[i] = new int[INITIAL_QUEUE_SIZE];
            }
        }

        private void releaseReferences() {
            Arrays.fill(skylessChunks, null);
            Arrays.fill(skylessQueueSizes, 0);
            for (int i = 0; i < skylessQueues.length; ++i) {
                if (skylessQueues[i].length > MAX_RETAINED_QUEUE_SIZE) {
                    skylessQueues[i] = new int[INITIAL_QUEUE_SIZE];
                }
            }
            if (skylessBlockSourceIndices.length > MAX_RETAINED_QUEUE_SIZE) {
                skylessBlockSourceIndices = new int[INITIAL_QUEUE_SIZE];
                skylessBlockSourceLevels = new byte[INITIAL_QUEUE_SIZE];
            }
            skylessBlockSourceCount = 0;
        }

        private void relightSkylessChunk(World world, Chunk center) {
            Arrays.fill(skylessLight, (byte) 0);
            Arrays.fill(skylessOpacity, (byte) 1);
            Arrays.fill(skylessQueueSizes, 0);
            buildSkylessChunkGrid(world, center);
            seedSkylessSources(center, true);

            for (int level = 15; level > 1; --level) {
                int[] queue = skylessQueues[level];
                for (int head = 0; head < skylessQueueSizes[level]; ++head) {
                    int index = queue[head];
                    if ((skylessLight[index] & 15) != level) continue; // Ignore an older queue entry after this block was improved.

                    int y = index & 255;
                    int column = index >> 8;

                    if (SKYLESS_CAN_SPREAD_NEG_X[column]) spreadSkylessLight(index - SKYLESS_X_STRIDE, level);
                    if (SKYLESS_CAN_SPREAD_POS_X[column]) spreadSkylessLight(index + SKYLESS_X_STRIDE, level);
                    if (y > 0) spreadSkylessLight(index - 1, level);
                    if (y < 255) spreadSkylessLight(index + 1, level);
                    if (SKYLESS_CAN_SPREAD_NEG_Z[column]) spreadSkylessLight(index - SKYLESS_Z_STRIDE, level);
                    if (SKYLESS_CAN_SPREAD_POS_Z[column]) spreadSkylessLight(index + SKYLESS_Z_STRIDE, level);
                }
            }

            writeSkylessLight(center);
            Arrays.fill(skylessChunks, null);
        }

        private boolean relightChunk(World world, Chunk center) {
            if (world.worldProvider.o()) {
                relightSkylessChunk(world, center);
                return true;
            }

            Arrays.fill(skylessLight, (byte) 0);
            Arrays.fill(skylessOpacity, (byte) 1);
            Arrays.fill(skylessQueueSizes, 0);
            buildSkylessChunkGrid(world, center);

            for (Chunk chunk : skylessChunks) {
                if (chunk == null) {
                    Arrays.fill(skylessChunks, null);
                    return false;
                }
            }

            clearSkyLight(center);
            skylessBlockSourceCount = 0;
            seedSkySources();
            propagateSkylessQueues();
            writeSkyLight(center);

            Arrays.fill(skylessLight, (byte) 0);
            Arrays.fill(skylessQueueSizes, 0);
            restoreBlockSources(center);
            propagateSkylessQueues();
            writeSkylessLight(center);

            Arrays.fill(skylessChunks, null);
            return true;
        }

        private boolean relightSkyChunk(World world, Chunk center) {
            if (world.worldProvider.o()) {
                return true;
            }

            Arrays.fill(skylessLight, (byte) 0);
            Arrays.fill(skylessOpacity, (byte) 1);
            Arrays.fill(skylessQueueSizes, 0);
            buildSkylessChunkGrid(world, center);

            for (Chunk chunk : skylessChunks) {
                if (chunk == null) {
                    Arrays.fill(skylessChunks, null);
                    return false;
                }
            }

            clearSkyLight(center);
            seedSkySources();

            propagateSkylessQueues();

            writeSkyLight(center);
            Arrays.fill(skylessChunks, null);
            return true;
        }

        private void buildSkylessChunkGrid(World world, Chunk center) {
            for (int chunkX = 0; chunkX < SKYLESS_CHUNK_GRID; ++chunkX) {
                for (int chunkZ = 0; chunkZ < SKYLESS_CHUNK_GRID; ++chunkZ) {
                    skylessChunks[chunkX * SKYLESS_CHUNK_GRID + chunkZ] =
                            world.getChunkIfLoaded(center.locX + chunkX - 1, center.locZ + chunkZ - 1);
                }
            }
        }

        private void seedSkylessSources(Chunk center, boolean seedOpacity) {
            clearBlockLight(center);

            for (int x = 0; x < SKYLESS_DIAMETER; ++x) {
                int chunkGridX = SKYLESS_CHUNK_GRID_X[x];
                int localX = SKYLESS_LOCAL_X[x];

                for (int z = 0; z < SKYLESS_DIAMETER; ++z) {
                    int chunkGridZ = SKYLESS_CHUNK_GRID_Z[z];
                    Chunk chunk = skylessChunks[chunkGridX * SKYLESS_CHUNK_GRID + chunkGridZ];
                    int localZ = SKYLESS_LOCAL_Z[z];
                    int baseIndex = SKYLESS_COLUMN_BASE[x * SKYLESS_DIAMETER + z];

                    if (chunk == null) {
                        if (seedOpacity) {
                            for (int y = 0; y < 256; ++y) {
                                skylessOpacity[baseIndex | y] = 15;
                            }
                        }
                        continue;
                    }

                    ChunkSection[] sections = chunk.getSections();
                    for (int sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
                        ChunkSection section = sections[sectionIndex];
                        if (section == null) {
                            continue;
                        }

                        int yBase = sectionIndex << 4;
                        char[] blockIds = section.getIdArray();
                        int columnIndex = (localZ << 4) | localX;
                        for (int localY = 0; localY < 16; ++localY) {
                            int index = baseIndex | (yBase | localY);
                            int stateId = blockIds[(localY << 8) | columnIndex];
                            if (seedOpacity) {
                                skylessOpacity[index] = stateId < STATE_COUNT ? LIGHT_OPACITY[stateId] : 1;
                            }
                            int emitted = stateId < STATE_COUNT ? LIGHT_EMISSION[stateId] & 15 : 0;
                            if (emitted > 0) {
                                skylessLight[index] = (byte) emitted;
                                queueSkylessLight(emitted, index);
                            }
                        }
                    }
                }
            }
        }

        private void propagateSkylessQueues() {
            for (int level = 15; level > 1; --level) {
                int[] queue = skylessQueues[level];
                for (int head = 0; head < skylessQueueSizes[level]; ++head) {
                    int index = queue[head];
                    if ((skylessLight[index] & 15) != level) continue;

                    int y = index & 255;
                    int column = index >> 8;

                    if (SKYLESS_CAN_SPREAD_NEG_X[column]) spreadSkylessLight(index - SKYLESS_X_STRIDE, level);
                    if (SKYLESS_CAN_SPREAD_POS_X[column]) spreadSkylessLight(index + SKYLESS_X_STRIDE, level);
                    if (y > 0) spreadSkylessLight(index - 1, level);
                    if (y < 255) spreadSkylessLight(index + 1, level);
                    if (SKYLESS_CAN_SPREAD_NEG_Z[column]) spreadSkylessLight(index - SKYLESS_Z_STRIDE, level);
                    if (SKYLESS_CAN_SPREAD_POS_Z[column]) spreadSkylessLight(index + SKYLESS_Z_STRIDE, level);
                }
            }
        }

        private void spreadSkylessLight(int index, int sourceLight) {
            int nextLight = sourceLight - (skylessOpacity[index] & 0xFF);
            if (nextLight <= 0) return;

            if ((skylessLight[index] & 15) >= nextLight) return;

            skylessLight[index] = (byte) nextLight;
            queueSkylessLight(nextLight, index);
        }

        private void queueSkylessLight(int level, int index) {
            int[] queue = skylessQueues[level];
            int size = skylessQueueSizes[level];
            if (size >= queue.length) {
                queue = skylessQueues[level] = Arrays.copyOf(queue, queue.length << 1);
            }

            queue[size] = index;
            skylessQueueSizes[level] = size + 1;
        }

        private void clearBlockLight(Chunk chunk) {
            for (ChunkSection section : chunk.getSections()) {
                if (section != null) {
                    Arrays.fill(section.getEmittedLightArray().a(), (byte) 0);
                }
            }
        }

        private void clearSkyLight(Chunk chunk) {
            for (ChunkSection section : chunk.getSections()) {
                if (section != null) {
                    Arrays.fill(section.getSkyLightArray().a(), (byte) 0);
                }
            }
        }

        private void seedSkySources() {
            for (int x = 0; x < SKYLESS_DIAMETER; ++x) {
                int chunkGridX = SKYLESS_CHUNK_GRID_X[x];
                int localX = SKYLESS_LOCAL_X[x];

                for (int z = 0; z < SKYLESS_DIAMETER; ++z) {
                    int chunkGridZ = SKYLESS_CHUNK_GRID_Z[z];
                    Chunk chunk = skylessChunks[chunkGridX * SKYLESS_CHUNK_GRID + chunkGridZ];
                    int localZ = SKYLESS_LOCAL_Z[z];
                    int baseIndex = SKYLESS_COLUMN_BASE[x * SKYLESS_DIAMETER + z];

                    ChunkSection[] sections = chunk.getSections();
                    for (int sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
                        ChunkSection section = sections[sectionIndex];
                        if (section == null) {
                            continue;
                        }

                        int yBase = sectionIndex << 4;
                        char[] blockIds = section.getIdArray();
                        int columnIndex = (localZ << 4) | localX;
                        for (int localY = 0; localY < 16; ++localY) {
                            int index = baseIndex | (yBase | localY);
                            int stateId = blockIds[(localY << 8) | columnIndex];
                            skylessOpacity[index] = stateId < STATE_COUNT ? LIGHT_OPACITY[stateId] : 1;
                            int emitted = stateId < STATE_COUNT ? LIGHT_EMISSION[stateId] & 15 : 0;
                            if (emitted > 0) {
                                recordBlockSource(index, emitted);
                            }
                        }
                    }

                    int skyStart = chunk.heightMap[(localZ << 4) | localX];
                    for (int y = skyStart; y < 256; ++y) {
                        skylessLight[baseIndex | y] = 15;
                    }

                    int queueUntil = skyStart + 1;
                    if (x == 0 || x == SKYLESS_DIAMETER - 1 || z == 0 || z == SKYLESS_DIAMETER - 1) {
                        queueUntil = 256;
                    } else {
                        int neighborSkyStart = getSkyStart(x - 1, z);
                        if (neighborSkyStart > queueUntil) queueUntil = neighborSkyStart;
                        neighborSkyStart = getSkyStart(x + 1, z);
                        if (neighborSkyStart > queueUntil) queueUntil = neighborSkyStart;
                        neighborSkyStart = getSkyStart(x, z - 1);
                        if (neighborSkyStart > queueUntil) queueUntil = neighborSkyStart;
                        neighborSkyStart = getSkyStart(x, z + 1);
                        if (neighborSkyStart > queueUntil) queueUntil = neighborSkyStart;
                        if (queueUntil > 256) queueUntil = 256;
                    }

                    for (int y = skyStart; y < queueUntil; ++y) {
                        queueSkylessLight(15, baseIndex | y);
                    }
                }
            }
        }

        private int getSkyStart(int x, int z) {
            int chunkGridX = SKYLESS_CHUNK_GRID_X[x];
            int chunkGridZ = SKYLESS_CHUNK_GRID_Z[z];
            Chunk chunk = skylessChunks[chunkGridX * SKYLESS_CHUNK_GRID + chunkGridZ];
            return chunk.heightMap[(SKYLESS_LOCAL_Z[z] << 4) | SKYLESS_LOCAL_X[x]];
        }

        private void recordBlockSource(int index, int emitted) {
            int size = skylessBlockSourceCount;
            if (size >= skylessBlockSourceIndices.length) {
                skylessBlockSourceIndices = Arrays.copyOf(skylessBlockSourceIndices, size << 1);
                skylessBlockSourceLevels = Arrays.copyOf(skylessBlockSourceLevels, size << 1);
            }

            skylessBlockSourceIndices[size] = index;
            skylessBlockSourceLevels[size] = (byte) emitted;
            skylessBlockSourceCount = size + 1;
        }

        private void restoreBlockSources(Chunk center) {
            clearBlockLight(center);

            for (int i = 0; i < skylessBlockSourceCount; ++i) {
                int index = skylessBlockSourceIndices[i];
                int emitted = skylessBlockSourceLevels[i] & 15;
                skylessLight[index] = (byte) emitted;
                queueSkylessLight(emitted, index);
            }
        }

        private void writeSkyLight(Chunk chunk) {
            ChunkSection[] sections = chunk.getSections();

            for (int localX = 0; localX < 16; ++localX) {
                int paddedX = localX + SKYLESS_RADIUS;
                for (int localZ = 0; localZ < 16; ++localZ) {
                    int paddedZ = localZ + SKYLESS_RADIUS;
                    int baseIndex = (paddedX * SKYLESS_DIAMETER + paddedZ) << 8;
                    int skyStart = chunk.heightMap[(localZ << 4) | localX];
                    for (int y = 0; y < 256; ++y) {
                        int skyLight = skylessLight[baseIndex | y] & 15;
                        if (skyLight == 0) continue;

                        ChunkSection section = sections[y >> 4];
                        if (section == null) {
                            if (y >= skyStart) {
                                continue;
                            }
                            section = sections[y >> 4] = new ChunkSection(y >> 4 << 4, true);
                        }
                        int localIndex = ((y & 15) << 8) | (localZ << 4) | localX;
                        byte[] light = section.getSkyLightArray().a();
                        light[localIndex >> 1] |= (byte) (skyLight << ((localIndex & 1) << 2));
                    }
                }
            }

            // Reassign the arrays so servers that track non-empty light counts can refresh
            // their metadata once per section instead of once per block.
            for (ChunkSection section : sections) {
                if (section != null) {
                    section.b(section.getSkyLightArray());
                }
            }
        }

        private void writeSkylessLight(Chunk chunk) {
            ChunkSection[] sections = chunk.getSections();

            for (int localX = 0; localX < 16; ++localX) {
                int paddedX = localX + SKYLESS_RADIUS;
                for (int localZ = 0; localZ < 16; ++localZ) {
                    int paddedZ = localZ + SKYLESS_RADIUS;
                    int baseIndex = (paddedX * SKYLESS_DIAMETER + paddedZ) << 8;
                    for (int y = 0; y < 256; ++y) {
                        int blockLight = skylessLight[baseIndex | y] & 15;
                        if (blockLight == 0) continue;

                        ChunkSection section = sections[y >> 4];
                        if (section == null) {
                            section = sections[y >> 4] = new ChunkSection(y >> 4 << 4, !chunk.world.worldProvider.o());
                            if (!chunk.world.worldProvider.o()) {
                                initNullSectionSkyLight(chunk, section);
                            }
                        }
                        int localIndex = ((y & 15) << 8) | (localZ << 4) | localX;
                        byte[] light = section.getEmittedLightArray().a();
                        light[localIndex >> 1] |= (byte) (blockLight << ((localIndex & 1) << 2));
                    }
                }
            }

            for (ChunkSection section : sections) {
                if (section != null) {
                    section.a(section.getEmittedLightArray());
                }
            }
        }

        private void initNullSectionSkyLight(Chunk chunk, ChunkSection section) {
            int sectionY = section.getYPosition();

            for (int localX = 0; localX < 16; ++localX) {
                for (int localZ = 0; localZ < 16; ++localZ) {
                    int skyStart = chunk.heightMap[(localZ << 4) | localX];
                    for (int localY = 0; localY < 16; ++localY) {
                        int worldY = sectionY + localY;
                        if (worldY >= skyStart) {
                            section.a(localX, localY, localZ, 15);
                        }
                    }
                }
            }
        }

    }
}

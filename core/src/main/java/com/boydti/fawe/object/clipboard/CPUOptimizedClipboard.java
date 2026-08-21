package com.boydti.fawe.object.clipboard;

import com.boydti.fawe.FaweCache;
import com.boydti.fawe.jnbt.NBTStreamer;
import com.boydti.fawe.object.IntegerTrio;
import com.boydti.fawe.util.ReflectionUtils;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.IntTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.biome.BaseBiome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class CPUOptimizedClipboard extends FaweClipboard {
    private static final boolean[] NBT_BLOCK_IDS = new boolean[4096];

    static {
        for (int id = 0; id < NBT_BLOCK_IDS.length; id++) {
            NBT_BLOCK_IDS[id] = FaweCache.hasNBT(id);
        }
    }

    private int length;
    private int height;
    private int width;
    private int area;
    private int volume;

    private byte[] biomes = null;
    /** Packed legacy block state: 12-bit id followed by 4-bit data. */
    private char[] blocks;

    private final HashMap<IntegerTrio, CompoundTag> nbtMapLoc;
    private final HashMap<Integer, CompoundTag> nbtMapIndex;
    private volatile boolean tilesIndexed;

    private final HashSet<ClipboardEntity> entities;

    public CPUOptimizedClipboard(int width, int height, int length) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.area = width * length;
        this.volume = area * height;
        blocks = new char[volume];
        nbtMapLoc = new HashMap<>();
        nbtMapIndex = new HashMap<>();
        entities = new HashSet<>();
    }

    @Override
    public boolean hasBiomes() {
        return biomes != null;
    }

    @Override
    public boolean setBiome(int x, int z, int biome) {
        setBiome(getIndex(x, 0, z), biome);
        return true;
    }

    @Override
    public void setBiome(int index, int biome) {
        if (biomes == null) {
            biomes = new byte[area];
        }
        biomes[index] = (byte) biome;
    }

    @Override
    public void streamBiomes(NBTStreamer.ByteReader task) {
        if (!hasBiomes()) return;
        int index = 0;
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++, index++) {
                task.run(index, biomes[index] & 0xFF);
            }
        }
    }

    @Override
    public BaseBiome getBiome(int index) {
        if (!hasBiomes()) {
            return EditSession.nullBiome;
        }
        return FaweCache.CACHE_BIOME[biomes[index] & 0xFF];
    }

    @Override
    public BaseBiome getBiome(int x, int z) {
        return getBiome(getIndex(x, 0, z));
    }

    public void convertTilesToIndex() {
        if (tilesIndexed) {
            return;
        }
        synchronized (this) {
            if (tilesIndexed) {
                return;
            }
            for (Map.Entry<IntegerTrio, CompoundTag> entry : nbtMapLoc.entrySet()) {
                IntegerTrio key = entry.getKey();
                setTile(getIndex(key.x, key.y, key.z), entry.getValue());
            }
            nbtMapLoc.clear();
            tilesIndexed = true;
        }
    }

    private CompoundTag getTag(int index) {
        convertTilesToIndex();
        return nbtMapIndex.get(index);
    }

    public int getId(int index) {
        return blocks[index] >>> 4;
    }

    public int getAdd(int index) {
        return blocks[index] >>> 12;
    }

    public int getData(int index) {
        return blocks[index] & 0xF;
    }

    @Override
    public void setDimensions(Vector dimensions) {
        width = dimensions.getBlockX();
        height = dimensions.getBlockY();
        length = dimensions.getBlockZ();
        area = width * length;
        int newVolume = area * height;
        if (newVolume != volume) {
            volume = newVolume;
            blocks = new char[volume];
        }
    }

    @Override
    public Vector getDimensions() {
        return new Vector(width, height, length);
    }

    @Override
    public void setAdd(int index, int value) {
        blocks[index] = (char) ((blocks[index] & 0x0FFF) | ((value & 0xF) << 12));
    }

    @Override
    public void setId(int index, int value) {
        blocks[index] = (char) ((blocks[index] & 0xF00F) | ((value & 0xFF) << 4));
    }

    @Override
    public void setData(int index, int value) {
        blocks[index] = (char) ((blocks[index] & 0xFFF0) | (value & 0xF));
    }

    public int getIndex(int x, int y, int z) {
        return x + y * area + z * width;
    }

    @Override
    public BaseBlock getBlock(int x, int y, int z) {
        int index = getIndex(x, y, z);
        return getBlock(index);
    }

    @Override
    public BaseBlock getBlock(int index) {
        int combined = blocks[index];
        int id = combined >>> 4;
        if (id == 0) {
            return FaweCache.CACHE_BLOCK[0];
        }
        BaseBlock block = FaweCache.CACHE_BLOCK[combined];
        if (NBT_BLOCK_IDS[id] && (!tilesIndexed || !nbtMapIndex.isEmpty())) {
            CompoundTag nbt = getTag(index);
            if (nbt != null) {
                return new BaseBlock(id, combined & 0xF, nbt);
            }
        }
        return block;
    }

    @Override
    public void forEach(final BlockReader task, boolean air) {
        convertTilesToIndex();
        if (air) {
            for (int y = 0, index = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++, index++) {
                        task.run(x, y, z, getBlock(index));
                    }
                }
            }
        } else {
            for (int y = 0, index = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++, index++) {
                        if ((blocks[index] >>> 4) != 0) {
                            task.run(x, y, z, getBlock(index));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void streamIds(NBTStreamer.ByteReader task) {
        for (int index = 0; index < volume; index++) {
            task.run(index, blocks[index] >>> 4);
        }
    }

    /**
     * Copies the packed clipboard directly without allocating a Vector for every block.
     */
    public int copyNonAirTo(Extent destination, int destinationMinX, int destinationMinY,
                            int destinationMinZ) throws WorldEditException {
        return copyNonAirTo(destination, destinationMinX, destinationMinY, destinationMinZ,
                false);
    }

    /**
     * Copies the packed clipboard and, when requested, its two-dimensional biome array.
     */
    public int copyNonAirTo(Extent destination, int destinationMinX, int destinationMinY,
                            int destinationMinZ, boolean copyBiomes) throws WorldEditException {
        convertTilesToIndex();
        if (copyBiomes && hasBiomes()) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    destination.setBiome(destinationMinX + x, destinationMinY,
                            destinationMinZ + z, getBiome(x, z));
                }
            }
        }
        int affected = 0;
        for (int chunkZ = 0; chunkZ < length; chunkZ += 16) {
            int maximumZ = Math.min(length, chunkZ + 16);
            for (int chunkX = 0; chunkX < width; chunkX += 16) {
                int maximumX = Math.min(width, chunkX + 16);
                for (int y = 0; y < height; y++) {
                    int destinationY = destinationMinY + y;
                    for (int z = chunkZ; z < maximumZ; z++) {
                        int destinationZ = destinationMinZ + z;
                        int index = chunkX + y * area + z * width;
                        for (int x = chunkX; x < maximumX; x++, index++) {
                            int combined = blocks[index];
                            int id = combined >>> 4;
                            if (id == 0) {
                                continue;
                            }
                            BaseBlock block = FaweCache.CACHE_BLOCK[combined];
                            if (NBT_BLOCK_IDS[id] && !nbtMapIndex.isEmpty()) {
                                CompoundTag nbt = nbtMapIndex.get(index);
                                if (nbt != null) {
                                    block = new BaseBlock(id, combined & 0xF, nbt);
                                }
                            }
                            if (destination.setBlock(destinationMinX + x, destinationY,
                                    destinationZ, block)) {
                                affected++;
                            }
                        }
                    }
                }
            }
        }
        return affected;
    }

    @Override
    public void streamDatas(NBTStreamer.ByteReader task) {
        for (int index = 0; index < volume; index++) {
            task.run(index, blocks[index] & 0xF);
        }
    }

    @Override
    public List<CompoundTag> getTileEntities() {
        convertTilesToIndex();
        for (Map.Entry<Integer, CompoundTag> entry : nbtMapIndex.entrySet()) {
            int index = entry.getKey();
            CompoundTag tag = entry.getValue();
            Map<String, Tag> values = ReflectionUtils.getMap(tag.getValue());
            if (!values.containsKey("x")) {
                int y = index / area;
                index -= y * area;
                int z = index / width;
                int x = index - (z * width);
                values.put("x", new IntTag(x));
                values.put("y", new IntTag(y));
                values.put("z", new IntTag(z));
            }
        }
        return new ArrayList<>(nbtMapIndex.values());
    }

    @Override
    public boolean setTile(int x, int y, int z, CompoundTag tag) {
        synchronized (this) {
            nbtMapLoc.put(new IntegerTrio(x, y, z), tag);
            tilesIndexed = false;
        }
        return true;
    }

    public boolean setTile(int index, CompoundTag tag) {
        nbtMapIndex.put(index, tag);
        Map<String, Tag> values = ReflectionUtils.getMap(tag.getValue());
        values.remove("x");
        values.remove("y");
        values.remove("z");
        return true;
    }

    @Override
    public boolean setBlock(int x, int y, int z, BaseBlock block) {
        return setBlock(getIndex(x, y, z), block);
    }

    public boolean setBlock(int index, BaseBlock block) {
        int id = block.getId();
        blocks[index] = (char) ((id << 4) | (block.getData() & 0xF));
        CompoundTag tile = block.getNbtData();
        if (tile != null) {
            setTile(index, tile);
        }
        return true;
    }

    @Override
    public Entity createEntity(Extent world, double x, double y, double z, float yaw, float pitch, BaseEntity entity) {
        FaweClipboard.ClipboardEntity ret = new ClipboardEntity(world, x, y, z, yaw, pitch, entity);
        entities.add(ret);
        return ret;
    }

    @Override
    public List<? extends Entity> getEntities() {
        return new ArrayList<>(entities);
    }

    @Override
    public boolean remove(ClipboardEntity clipboardEntity) {
        return entities.remove(clipboardEntity);
    }
}

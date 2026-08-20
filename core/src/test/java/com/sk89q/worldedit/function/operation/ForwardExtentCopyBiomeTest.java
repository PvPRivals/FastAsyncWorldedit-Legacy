package com.sk89q.worldedit.function.operation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.boydti.fawe.object.extent.EmptyExtent;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.biome.BaseBiome;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class ForwardExtentCopyBiomeTest {

    @Test
    public void copiesEachBiomeColumnOnceRegardlessOfHeight() {
        CuboidRegion region = new CuboidRegion(new Vector(0, 0, 0), new Vector(3, 31, 2));
        CountingSource source = new CountingSource();
        CountingDestination destination = new CountingDestination();
        ForwardExtentCopy copy = new ForwardExtentCopy(source, region, destination, new Vector(16, 10, 32));
        copy.setCopyBiomes(true);
        copy.setCopyEntities(false);

        Operations.completeBlindly(copy);

        assertEquals(12, source.biomeReads);
        assertEquals(12, destination.biomeWrites);
        assertEquals(12, destination.columns.size());
        assertTrue(destination.columns.contains("16:32"));
        assertTrue(destination.columns.contains("19:34"));
    }

    private static final class CountingSource extends EmptyExtent {
        private final BaseBiome biome = new BaseBiome(1);
        private int biomeReads;

        @Override
        public BaseBiome getBiome(Vector2D position) {
            biomeReads++;
            return biome;
        }
    }

    private static final class CountingDestination extends EmptyExtent {
        private int biomeWrites;
        private final Set<String> columns = new HashSet<>();

        @Override
        public boolean setBiome(Vector2D position, BaseBiome biome) {
            biomeWrites++;
            columns.add(position.getBlockX() + ":" + position.getBlockZ());
            return true;
        }
    }
}

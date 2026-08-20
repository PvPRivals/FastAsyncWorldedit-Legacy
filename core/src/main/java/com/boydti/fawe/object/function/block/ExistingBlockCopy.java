package com.boydti.fawe.object.function.block;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.RegionFunction;

public class ExistingBlockCopy implements RegionFunction {

    private final Extent source;
    private final Extent destination;

    public ExistingBlockCopy(Extent source, Extent destination) {
        this.source = source;
        this.destination = destination;
    }

    @Override
    public boolean apply(Vector position) throws WorldEditException {
        BaseBlock block = source.getBlock(position);
        if (block.getId() == 0) {
            return false;
        }
        return destination.setBlock(position.getBlockX(), position.getBlockY(), position.getBlockZ(), block);
    }
}

package rwg2.mixin;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.emoniph.witchery.worldgen.WorldHandlerVillageDistrict;

/**
 * Witchery builds town walls from a temporary tile entity that waits 40 ticks, but keeps the wall layout only in
 * memory. If the chunk unloads first (pregeneration, fast travel, a save and quit), the layout is lost and the wall is
 * never built. If it does fire, it builds the whole wall in one pass, and any part crossing a chunk that is not loaded
 * is lost, leaving gaps. This saves the pending layout with the tile entity, and replaces the fixed delay with a
 * readiness check: the wall is built on the first tick every chunk under it is loaded and populated. It never loads or
 * generates a chunk itself, so it cannot cascade worldgen.
 */
@Mixin(value = WorldHandlerVillageDistrict.Wall.BlockVillageWallGen.TileEntityVillageWallGen.class, remap = false)
public abstract class MixinWitcheryVillageWallGen extends TileEntity {

    // Expansions Witchery applies to each village path in TileEntityVillageWallGen.setStructure.
    private static final int EXPANSION_ALONG = 20;
    private static final int EXPANSION_ACROSS = 7;
    private static final String TAG = "RWG2PendingWall";
    // placeWalls touches one block outside its layout box on the low side and three on the high side.
    private static final int FOOTPRINT_MARGIN = 4;
    private static final int READINESS_CHECK_INTERVAL = 5;

    private int rwg2$readinessCountdown;

    @Shadow(remap = false)
    private List bb;
    @Shadow(remap = false)
    private BiomeGenBase biome;
    @Shadow(remap = false)
    private boolean desert;

    @Inject(method = "updateEntity", at = @At("HEAD"), cancellable = true, remap = true)
    private void rwg2$buildWhenFootprintReady(CallbackInfo callback) {
        if (worldObj == null || worldObj.isRemote || bb == null) return; // Witchery's own cleanup handles the rest.
        callback.cancel();
        if (rwg2$readinessCountdown-- > 0) return;
        rwg2$readinessCountdown = READINESS_CHECK_INTERVAL;
        if (!rwg2$isFootprintReady()) return;
        List layout = bb;
        bb = null;
        WorldHandlerVillageDistrict.Wall.placeWalls(worldObj, layout, xCoord, yCoord, zCoord, biome, desert);
        worldObj.setBlockToAir(xCoord, yCoord, zCoord);
    }

    private boolean rwg2$isFootprintReady() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object entry : bb) {
            WorldHandlerVillageDistrict.Wall.StructureBounds bounds = (WorldHandlerVillageDistrict.Wall.StructureBounds) entry;
            minX = Math.min(minX, bounds.minX);
            minZ = Math.min(minZ, bounds.minZ);
            maxX = Math.max(maxX, bounds.maxX);
            maxZ = Math.max(maxZ, bounds.maxZ);
        }
        if (minX > maxX) return true; // Empty layout: let placeWalls no-op and clear the block.
        IChunkProvider provider = worldObj.getChunkProvider();
        for (int chunkX = (minX - FOOTPRINT_MARGIN) >> 4; chunkX <= (maxX + FOOTPRINT_MARGIN) >> 4; chunkX++) {
            for (int chunkZ = (minZ - FOOTPRINT_MARGIN) >> 4; chunkZ <= (maxZ + FOOTPRINT_MARGIN) >> 4; chunkZ++) {
                // On the server chunkExists only reports chunks already in memory; it never loads one.
                if (!provider.chunkExists(chunkX, chunkZ)) return false;
                Chunk chunk = provider.provideChunk(chunkX, chunkZ);
                if (!chunk.isTerrainPopulated) return false;
            }
        }
        return true;
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        if (bb == null || bb.isEmpty()) return;
        NBTTagCompound pending = new NBTTagCompound();
        NBTTagList paths = new NBTTagList();
        for (Object entry : bb) {
            WorldHandlerVillageDistrict.Wall.StructureBounds bounds = (WorldHandlerVillageDistrict.Wall.StructureBounds) entry;
            int alongX = bounds.ew ? EXPANSION_ACROSS : EXPANSION_ALONG;
            int alongZ = bounds.ew ? EXPANSION_ALONG : EXPANSION_ACROSS;
            // Store the original path box, so the bounds can be rebuilt exactly through Witchery's own constructor.
            NBTTagCompound path = new NBTTagCompound();
            path.setIntArray(
                    "Box",
                    new int[] { bounds.minX + alongX, bounds.minY, bounds.minZ + alongZ, bounds.maxX - alongX,
                            bounds.maxY, bounds.maxZ - alongZ });
            paths.appendTag(path);
        }
        pending.setTag("Paths", paths);
        pending.setInteger("Biome", biome == null ? -1 : biome.biomeID);
        pending.setBoolean("Desert", desert);
        nbt.setTag(TAG, pending);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        if (!nbt.hasKey(TAG)) return;
        NBTTagCompound pending = nbt.getCompoundTag(TAG);
        NBTTagList paths = pending.getTagList("Paths", 10);
        List restored = new ArrayList();
        for (int i = 0; i < paths.tagCount(); i++) {
            int[] box = paths.getCompoundTagAt(i).getIntArray("Box");
            if (box.length != 6) continue;
            restored.add(
                    new WorldHandlerVillageDistrict.Wall.StructureBounds(
                            box[0],
                            box[1],
                            box[2],
                            box[3],
                            box[4],
                            box[5],
                            EXPANSION_ALONG,
                            EXPANSION_ACROSS));
        }
        if (restored.isEmpty()) return;
        int biomeId = pending.getInteger("Biome");
        BiomeGenBase[] biomes = BiomeGenBase.getBiomeGenArray();
        biome = biomeId >= 0 && biomeId < biomes.length ? biomes[biomeId] : null;
        desert = pending.getBoolean("Desert");
        bb = restored;
    }
}

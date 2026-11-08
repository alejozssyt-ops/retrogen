package com.alejandro.retrogenmods;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.List;

/**
 * Repinta los biomas de un chunk ya generado con lo que daría el generador de biomas ACTUAL
 * (por ejemplo, con Terralith o WWOO instalados). Solo cambia datos de bioma: NO toca bloques,
 * así que no destruye construcciones. Si el bioma cambió, vuelve a enviar el chunk a los
 * clientes y genera fauna inicial del nuevo bioma.
 */
public final class BiomeRepaint {
	private BiomeRepaint() {
	}

	/** @return true si el bioma central del chunk cambió. */
	public static boolean apply(ServerLevel level, LevelChunk chunk) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState randomState = level.getChunkSource().randomState();
		int cx = chunk.getPos().getMinBlockX() + 8;
		int cz = chunk.getPos().getMinBlockZ() + 8;
		int qy = level.getSeaLevel() >> 2;

		Holder<Biome> before = chunk.getNoiseBiome(cx >> 2, qy, cz >> 2);
		chunk.fillBiomesFromNoise(generator.getBiomeSource(), randomState.sampler());
		chunk.markUnsaved();
		Holder<Biome> after = chunk.getNoiseBiome(cx >> 2, qy, cz >> 2);

		boolean changed = !before.getRegisteredName().equals(after.getRegisteredName());
		if (changed) {
			level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
			try {
				RandomSource random = RandomSource.create(level.getSeed() ^ chunk.getPos().toLong());
				NaturalSpawner.spawnMobsForChunkGeneration(level, after, chunk.getPos(), random);
			} catch (Exception e) {
				RetrogenMods.LOGGER.warn("No se pudo generar fauna inicial en {}", chunk.getPos(), e);
			}
			RetrogenMods.LOGGER.info("Bioma repintado en {}: {} -> {}", chunk.getPos(), before.getRegisteredName(), after.getRegisteredName());
		}
		return changed;
	}
}

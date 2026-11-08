package com.alejandro.retrogenmods;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Coloca las PlacedFeature (árboles, flores, rocas...) de un mod en un chunk ya generado.
 * Las features del mod llegan a los biomas por BiomeModifications, así que las leemos
 * de los biomas del chunk y filtramos por namespace.
 */
public final class FeatureRetrogen {
	private FeatureRetrogen() {
	}

	// Puntos del chunk donde muestreamos el bioma (centro + 4 esquinas).
	private static final int[][] SAMPLES = {{8, 8}, {1, 1}, {14, 1}, {1, 14}, {14, 14}};

	public static int apply(ServerLevel level, LevelChunk chunk, String namespace) {
		ChunkPos cp = chunk.getPos();
		int minX = cp.getMinBlockX();
		int minZ = cp.getMinBlockZ();
		BlockPos origin = new BlockPos(minX, level.getMinY(), minZ);
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		String prefix = namespace + ":";

		// paso de decoración -> features (respeta el orden de generación de vanilla)
		TreeMap<Integer, Map<Holder<PlacedFeature>, Boolean>> bySteps = new TreeMap<>();
		for (int[] s : SAMPLES) {
			Holder<Biome> biome = level.getBiome(new BlockPos(minX + s[0], level.getSeaLevel(), minZ + s[1]));
			List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
			for (int step = 0; step < steps.size(); step++) {
				for (Holder<PlacedFeature> feature : steps.get(step)) {
					if (feature.getRegisteredName().startsWith(prefix)) {
						bySteps.computeIfAbsent(step, k -> new LinkedHashMap<>()).put(feature, Boolean.TRUE);
					}
				}
			}
		}

		RandomSource random = RandomSource.create(level.getSeed() ^ cp.toLong() ^ namespace.hashCode());
		int placed = 0;
		for (Map<Holder<PlacedFeature>, Boolean> features : bySteps.values()) {
			for (Holder<PlacedFeature> feature : new ArrayList<>(features.keySet())) {
				try {
					// placeWithBiomeCheck registra la feature actual para que el BiomeFilter funcione.
					if (feature.value().placeWithBiomeCheck(level, generator, random, origin)) {
						placed++;
					}
				} catch (Exception e) {
					RetrogenMods.LOGGER.warn("Falló la feature {} en el chunk {}", feature.getRegisteredName(), cp, e);
				}
			}
		}
		if (placed > 0) {
			StructureRetrogen.relight(level, cp.x, cp.z);
		}
		return placed;
	}
}

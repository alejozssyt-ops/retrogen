package com.alejandro.retrogenmods;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Genera estructuras de un mod en chunks ya existentes. Como las estructuras (islas) abarcan
 * varios chunks y los vecinos ya están generados, colocamos la estructura en TODOS los chunks
 * que cubre. Si alguno está en zona protegida o muy visitado, se descarta la estructura entera.
 */
public final class StructureRetrogen {
	private StructureRetrogen() {
	}

	private static final int MAX_CHUNKS = 64;

	public static int apply(ServerLevel level, LevelChunk chunk, String namespace) {
		ChunkPos cp = chunk.getPos();
		String prefix = namespace + ":";
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
		RandomState randomState = level.getChunkSource().randomState();
		StructureTemplateManager templates = level.getServer().getStructureManager();
		StructureManager structureManager = level.structureManager();
		long seed = level.getSeed();
		int generated = 0;

		Registry<StructureSet> sets = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
		for (Holder.Reference<StructureSet> setHolder : sets.listElements().toList()) {
			if (!setHolder.getRegisteredName().startsWith(prefix)) {
				continue;
			}
			StructureSet set = setHolder.value();
			StructurePlacement placement = set.placement();
			if (!placement.isStructureChunk(state, cp.x, cp.z)) {
				continue;
			}
			RetrogenMods.LOGGER.info("[{}] {} es chunk de estructura del set {}", namespace, cp, setHolder.getRegisteredName());

			RandomSource random = RandomSource.create(seed ^ cp.toLong() ^ setHolder.getRegisteredName().hashCode());
			List<StructureSet.StructureSelectionEntry> candidates = new ArrayList<>(set.structures());
			while (!candidates.isEmpty()) {
				StructureSet.StructureSelectionEntry entry = pickWeighted(candidates, random);
				candidates.remove(entry);
				Structure structure = entry.structure().value();
				String name = entry.structure().getRegisteredName();
				if (chunk.getStartForStructure(structure) != null) {
					break;
				}
				try {
					Predicate<Holder<Biome>> validBiome = structure.biomes()::contains;
					StructureStart start = structure.generate(
							entry.structure(), level.dimension(), level.registryAccess(), generator,
							generator.getBiomeSource(), randomState, templates, seed, cp, 0, chunk, validBiome);
					if (!start.isValid()) {
						RetrogenMods.LOGGER.info("[{}] {} descartada en {}: no hay un sitio/bioma válido", namespace, name, cp);
						continue;
					}
					BoundingBox box = start.getBoundingBox();
					int minCx = SectionPos.blockToSectionCoord(box.minX());
					int maxCx = SectionPos.blockToSectionCoord(box.maxX());
					int minCz = SectionPos.blockToSectionCoord(box.minZ());
					int maxCz = SectionPos.blockToSectionCoord(box.maxZ());
					if ((maxCx - minCx + 1) * (maxCz - minCz + 1) > MAX_CHUNKS) {
						RetrogenMods.LOGGER.info("[{}] {} descartada en {}: demasiado grande", namespace, name, cp);
						continue;
					}
					if (!areaIsFree(level, minCx, maxCx, minCz, maxCz)) {
						RetrogenMods.LOGGER.info("[{}] {} descartada en {}: toca zona protegida o muy visitada", namespace, name, cp);
						continue;
					}
					structureManager.setStartForStructure(SectionPos.bottomOf(chunk), structure, start, chunk);
					for (int x = minCx; x <= maxCx; x++) {
						for (int z = minCz; z <= maxCz; z++) {
							ChunkPos p = new ChunkPos(x, z);
							BoundingBox area = new BoundingBox(p.getMinBlockX(), level.getMinY() + 1, p.getMinBlockZ(),
									p.getMaxBlockX(), level.getMaxY(), p.getMaxBlockZ());
							start.placeInChunk(level, structureManager, generator, random, area, p);
						}
					}
					generated++;
					RetrogenMods.LOGGER.info("[{}] GENERADA {} en {} (chunks {}..{}, {}..{})", namespace, name, cp, minCx, maxCx, minCz, maxCz);
					break;
				} catch (Exception e) {
					RetrogenMods.LOGGER.warn("Falló la estructura {} en el chunk {}", name, cp, e);
				}
			}
		}
		return generated;
	}

	private static boolean areaIsFree(ServerLevel level, int minCx, int maxCx, int minCz, int maxCz) {
		for (int x = minCx; x <= maxCx; x++) {
			for (int z = minCz; z <= maxCz; z++) {
				if (RetrogenMods.isProtected(level, new ChunkPos(x, z))) {
					return false;
				}
				ChunkAccess c = level.getChunk(x, z);
				if (RetrogenMods.isBusy(level, c)) {
					return false;
				}
			}
		}
		return true;
	}

	private static StructureSet.StructureSelectionEntry pickWeighted(List<StructureSet.StructureSelectionEntry> list, RandomSource random) {
		int total = 0;
		for (StructureSet.StructureSelectionEntry e : list) {
			total += Math.max(1, e.weight());
		}
		int roll = random.nextInt(total);
		for (StructureSet.StructureSelectionEntry e : list) {
			roll -= Math.max(1, e.weight());
			if (roll < 0) {
				return e;
			}
		}
		return list.get(0);
	}

	/** Lista los puntos de inicio de estructura más cercanos (cálculo puro, no genera nada). */
	public static String scan(ServerLevel level, ChunkPos center, int radius, List<String> namespaces) {
		ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
		Registry<StructureSet> sets = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
		record Hit(long dist, String name, int x, int z) {
		}
		List<Hit> hits = new ArrayList<>();
		for (Holder.Reference<StructureSet> h : sets.listElements().toList()) {
			String name = h.getRegisteredName();
			boolean match = false;
			for (String ns : namespaces) {
				if (name.startsWith(ns + ":")) {
					match = true;
				}
			}
			if (!match) {
				continue;
			}
			StructurePlacement placement = h.value().placement();
			for (int x = center.x - radius; x <= center.x + radius; x++) {
				for (int z = center.z - radius; z <= center.z + radius; z++) {
					if (placement.isStructureChunk(state, x, z)) {
						long dx = x - center.x;
						long dz = z - center.z;
						hits.add(new Hit(dx * dx + dz * dz, name, x, z));
					}
				}
			}
		}
		hits.sort(Comparator.comparingLong(Hit::dist));
		StringBuilder sb = new StringBuilder("Puntos de inicio en " + radius + " chunks: " + hits.size() + ". Más cercanos: ");
		for (int i = 0; i < Math.min(8, hits.size()); i++) {
			Hit hit = hits.get(i);
			sb.append("\n").append(hit.name()).append(" -> bloque ").append(hit.x() * 16 + 8).append(" ").append(hit.z() * 16 + 8);
		}
		return sb.toString();
	}
}

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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Genera estructuras de un mod en chunks ya existentes, repitiendo la lógica de
 * ChunkGenerator#tryGenerateStructure (que es privada) y colocando la estructura al momento.
 */
public final class StructureRetrogen {
	private StructureRetrogen() {
	}

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

			RandomSource random = RandomSource.create(seed ^ cp.toLong() ^ setHolder.getRegisteredName().hashCode());
			List<StructureSet.StructureSelectionEntry> candidates = new ArrayList<>(set.structures());
			while (!candidates.isEmpty()) {
				StructureSet.StructureSelectionEntry entry = pickWeighted(candidates, random);
				candidates.remove(entry);
				Structure structure = entry.structure().value();
				if (chunk.getStartForStructure(structure) != null) {
					break; // ya hay una estructura de este tipo aquí
				}
				try {
					Predicate<Holder<Biome>> validBiome = structure.biomes()::contains;
					StructureStart start = structure.generate(
							entry.structure(), level.dimension(), level.registryAccess(), generator,
							generator.getBiomeSource(), randomState, templates, seed, cp, 0, chunk, validBiome);
					if (!start.isValid()) {
						continue;
					}
					structureManager.setStartForStructure(SectionPos.bottomOf(chunk), structure, start, chunk);
					BoundingBox box = start.getBoundingBox();
					start.placeInChunk(level, structureManager, generator, random, box, cp);
					generated++;
					break;
				} catch (Exception e) {
					RetrogenMods.LOGGER.warn("Falló la estructura {} en el chunk {}", entry.structure().getRegisteredName(), cp, e);
				}
			}
		}
		return generated;
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
}

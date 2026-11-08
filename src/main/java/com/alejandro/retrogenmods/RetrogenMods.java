package com.alejandro.retrogenmods;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class RetrogenMods implements ModInitializer {
	public static final String MOD_ID = "retrogen_mods";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private record Pending(ServerLevel level, ChunkPos pos) {
	}

	private static final Deque<Pending> QUEUE = new ArrayDeque<>();
	private static final Set<Pending> QUEUED = new HashSet<>();
	private static RetrogenConfig config;

	@Override
	public void onInitialize() {
		config = RetrogenConfig.load();
		ChunkMarks.init();

		// Chunk cargado: si es nuevo, ya nació con los mods -> se marca como hecho.
		// Si viene de disco y le falta algún namespace -> a la cola.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
			boolean pending = false;
			for (String ns : config.namespaces) {
				String key = ChunkMarks.key(ns, config.version);
				if (newlyGenerated) {
					ChunkMarks.markApplied(chunk, key);
				} else if (!ChunkMarks.isApplied(chunk, key)) {
					pending = true;
				}
			}
			if (pending) {
				Pending p = new Pending(level, chunk.getPos());
				if (QUEUED.add(p)) {
					QUEUE.add(p);
				}
			}
		});

		// Procesa unos pocos chunks por tick para no congelar el servidor.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int budget = config.chunksPerTick;
			while (budget-- > 0 && !QUEUE.isEmpty()) {
				Pending p = QUEUE.poll();
				QUEUED.remove(p);
				LevelChunk chunk = p.level().getChunkSource().getChunkNow(p.pos().x, p.pos().z);
				if (chunk != null) {
					process(p.level(), chunk);
				}
			}
		});

		LOGGER.info("Retrogen Mods activo para: {}", config.namespaces);
	}

	private static void process(ServerLevel level, LevelChunk chunk) {
		for (String ns : config.namespaces) {
			String key = ChunkMarks.key(ns, config.version);
			if (ChunkMarks.isApplied(chunk, key)) {
				continue;
			}
			try {
				int structures = config.retrogenStructures ? StructureRetrogen.apply(level, chunk, ns) : 0;
				int features = config.retrogenFeatures ? FeatureRetrogen.apply(level, chunk, ns) : 0;
				if (structures + features > 0) {
					LOGGER.debug("Retrogen {} en {}: {} estructuras, {} features", ns, chunk.getPos(), structures, features);
				}
			} catch (Exception e) {
				LOGGER.error("Retrogen falló para {} en {}", ns, chunk.getPos(), e);
			}
			ChunkMarks.markApplied(chunk, key);
		}
	}
}

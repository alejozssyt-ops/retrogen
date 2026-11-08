package com.alejandro.retrogenmods;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

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

		// Chunk recién generado: ya nació con los mods -> se marca como hecho.
		ServerChunkEvents.CHUNK_GENERATE.register((level, chunk) -> {
			for (String ns : markNames()) {
				ChunkMarks.markApplied(chunk, ChunkMarks.key(ns, config.version));
			}
		});

		// Chunk cargado sin marca -> a la cola.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			boolean pending = false;
			for (String ns : markNames()) {
				if (!ChunkMarks.isApplied(chunk, ChunkMarks.key(ns, config.version))) {
					pending = true;
					break;
				}
			}
			if (pending) {
				Pending p = new Pending(level, chunk.getPos());
				if (QUEUED.add(p)) {
					QUEUE.add(p);
				}
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			AreaJob.tick(config.areaChunksPerTick);
			int budget = config.chunksPerTick;
			while (budget-- > 0 && !QUEUE.isEmpty()) {
				Pending p = QUEUE.poll();
				QUEUED.remove(p);
				LevelChunk chunk = p.level().getChunkSource().getChunkNow(p.pos().x, p.pos().z);
				if (chunk != null) {
					process(p.level(), chunk, false);
				}
			}
		});

		ServerLifecycleEvents.SERVER_STARTED.register(RetrogenMods::diagnose);

		// /retrogen here -> fuerza el retrogen en el chunk donde estás (para probar).
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("retrogen")
						.then(Commands.literal("scan")
								.then(Commands.argument("radio", IntegerArgumentType.integer(1, 150)).executes(ctx -> {
									CommandSourceStack src = ctx.getSource();
									ServerLevel level = src.getLevel();
									ChunkPos center = new ChunkPos(BlockPos.containing(src.getPosition()));
									String result = StructureRetrogen.scan(level, center, IntegerArgumentType.getInteger(ctx, "radio"), config.namespaces, null);
									LOGGER.info(result);
									src.sendSuccess(() -> Component.literal(result), false);
									return 1;
								}).then(Commands.argument("filtro", StringArgumentType.word()).executes(ctx -> {
									CommandSourceStack src = ctx.getSource();
									ChunkPos center = new ChunkPos(BlockPos.containing(src.getPosition()));
									String result = StructureRetrogen.scan(src.getLevel(), center, IntegerArgumentType.getInteger(ctx, "radio"), config.namespaces, StringArgumentType.getString(ctx, "filtro"));
									LOGGER.info(result);
									src.sendSuccess(() -> Component.literal(result), false);
									return 1;
								}))))
						.then(Commands.literal("area")
								.then(Commands.argument("lado", IntegerArgumentType.integer(1, 256)).executes(ctx -> {
									String result = AreaJob.start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "lado"), false);
									ctx.getSource().sendSuccess(() -> Component.literal(result), false);
									return 1;
								})))
						.then(Commands.literal("relight")
								.then(Commands.argument("lado", IntegerArgumentType.integer(1, 256)).executes(ctx -> {
									String result = AreaJob.start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "lado"), true);
									ctx.getSource().sendSuccess(() -> Component.literal(result), false);
									return 1;
								})))
						.then(Commands.literal("cancel").executes(ctx -> {
							String result = AreaJob.cancel() ? "Tarea cancelada." : "No hay ninguna tarea en curso.";
							ctx.getSource().sendSuccess(() -> Component.literal(result), false);
							return 1;
						}))
						.then(Commands.literal("sets").executes(ctx -> {
							String result = StructureRetrogen.listSets(ctx.getSource().getLevel(), config.namespaces);
							LOGGER.info("SETS: {}", result);
							ctx.getSource().sendSuccess(() -> Component.literal(result), false);
							return 1;
						}))
						.then(Commands.literal("here").executes(ctx -> {
							CommandSourceStack src = ctx.getSource();
							ServerLevel level = src.getLevel();
							LevelChunk chunk = level.getChunkAt(BlockPos.containing(src.getPosition()));
							String result = process(level, chunk, true);
							src.sendSuccess(() -> Component.literal(result), false);
							return 1;
						}))));

		LOGGER.info("Retrogen Mods activo para: {}", config.namespaces);
	}

	/** Al arrancar el mundo: muestra qué namespaces existen de verdad y avisa si alguno configurado no tiene nada. */
	private static void diagnose(MinecraftServer server) {
		var structureSets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
		var placedFeatures = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
		TreeMap<String, Integer> sets = new TreeMap<>();
		TreeMap<String, Integer> features = new TreeMap<>();
		for (Identifier id : structureSets.keySet()) {
			if (!id.getNamespace().equals("minecraft")) {
				sets.merge(id.getNamespace(), 1, Integer::sum);
			}
		}
		for (Identifier id : placedFeatures.keySet()) {
			if (!id.getNamespace().equals("minecraft")) {
				features.merge(id.getNamespace(), 1, Integer::sum);
			}
		}
		LOGGER.info("DIAGNÓSTICO structure sets de mods (namespace=cantidad): {}", sets);
		LOGGER.info("DIAGNÓSTICO placed features de mods (namespace=cantidad): {}", features);
		for (String ns : config.namespaces) {
			int s = sets.getOrDefault(ns, 0);
			int f = features.getOrDefault(ns, 0);
			if (s + f == 0) {
				LOGGER.warn("DIAGNÓSTICO: el namespace '{}' NO tiene structure sets ni features. Revisa el nombre en config/retrogen_mods.json (usa uno de la lista de arriba).", ns);
			} else {
				LOGGER.info("DIAGNÓSTICO: '{}' tiene {} structure sets y {} placed features", ns, s, f);
			}
		}
	}

	private static boolean unprotected(ServerLevel level) {
		String dimension = level.dimension().toString();
		for (String d : config.unprotectedDimensions) {
			if (dimension.contains(d)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isProtected(ServerLevel level, ChunkPos pos) {
		if (unprotected(level)) {
			return false;
		}
		String dimension = level.dimension().toString();
		int centerX = pos.getMinBlockX() + 8;
		int centerZ = pos.getMinBlockZ() + 8;
		for (RetrogenConfig.ProtectedZone zone : config.protectedZones) {
			if (zone.dimension != null && !dimension.contains(zone.dimension)) {
				continue;
			}
			int half = zone.diameterChunks * 16 / 2;
			if (Math.abs(centerX - zone.blockX) <= half && Math.abs(centerZ - zone.blockZ) <= half) {
				return true;
			}
		}
		return false;
	}

	public static boolean isBusy(ServerLevel level, ChunkAccess chunk) {
		return !unprotected(level) && config.skipStructuresIfInhabitedTicksAbove >= 0
				&& chunk.getInhabitedTime() > config.skipStructuresIfInhabitedTicksAbove;
	}

	/** Claves de marca de un chunk: cada namespace, más el repintado de biomas si está activo. */
	private static java.util.List<String> markNames() {
		java.util.List<String> names = new java.util.ArrayList<>(config.namespaces);
		if (config.repaintBiomes) {
			names.add("#repaint");
		}
		return names;
	}

	private static boolean repaintHere(ServerLevel level) {
		String dimension = level.dimension().toString();
		for (String d : config.repaintDimensions) {
			if (dimension.contains(d)) {
				return true;
			}
		}
		return false;
	}

	static void processChunk(ServerLevel level, LevelChunk chunk) {
		process(level, chunk, false);
	}

	/** @param force ignora las marcas (pero NO las zonas protegidas ni el tiempo habitado). */
	private static String process(ServerLevel level, LevelChunk chunk, boolean force) {
		boolean inProtectedZone = isProtected(level, chunk.getPos());
		boolean busyChunk = isBusy(level, chunk);
		StringBuilder summary = new StringBuilder();
		// 1) Repintar biomas primero, para que las features/estructuras usen el bioma nuevo.
		if (config.repaintBiomes) {
			String rkey = ChunkMarks.key("#repaint", config.version);
			if (force || !ChunkMarks.isApplied(chunk, rkey)) {
				if (!inProtectedZone && repaintHere(level)) {
					try {
						boolean changed = BiomeRepaint.apply(level, chunk);
						summary.append(changed ? "bioma repintado. " : "bioma sin cambios. ");
					} catch (Exception e) {
						LOGGER.error("Repintado de bioma falló en {}", chunk.getPos(), e);
					}
				}
				ChunkMarks.markApplied(chunk, rkey);
			}
		}
		for (String ns : config.namespaces) {
			String key = ChunkMarks.key(ns, config.version);
			if (!force && ChunkMarks.isApplied(chunk, key)) {
				continue;
			}
			if (inProtectedZone) {
				ChunkMarks.markApplied(chunk, key);
				summary.append(ns).append(": zona protegida, no se toca. ");
				continue;
			}
			int structures = 0;
			int features = 0;
			try {
				structures = config.retrogenStructures ? StructureRetrogen.apply(level, chunk, ns) : 0;
				features = config.retrogenFeatures ? FeatureRetrogen.apply(level, chunk, ns) : 0;
			} catch (Exception e) {
				LOGGER.error("Retrogen falló para {} en {}", ns, chunk.getPos(), e);
			}
			(force || structures + features > 0 ? LOGGER.atInfo() : LOGGER.atDebug()).log("Retrogen {} en {} ({}): {} estructuras, {} features{}", ns, chunk.getPos(), level.dimension().toString(), structures, features,
					busyChunk ? " (chunk muy visitado: solo estructuras de cielo)" : "");
			summary.append(ns).append(": ").append(structures).append(" estructuras, ").append(features).append(" features. ");
			ChunkMarks.markApplied(chunk, key);
		}
		return summary.length() == 0 ? "Nada que hacer en este chunk." : summary.toString();
	}
}

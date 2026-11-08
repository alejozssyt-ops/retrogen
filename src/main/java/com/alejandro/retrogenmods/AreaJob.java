package com.alejandro.retrogenmods;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tarea que recorre un cuadrado de hasta 256x256 chunks alrededor del jugador.
 * Solo procesa los chunks que YA existen en disco (no genera terreno nuevo) y muestra
 * una barra de progreso.
 */
public final class AreaJob {
	private static AreaJob current;

	private record Read(ChunkPos pos, CompletableFuture<Optional<CompoundTag>> future) {
	}

	private static final int READ_AHEAD = 64;

	private final MinecraftServer server;
	private final ServerLevel level;
	private final int minX;
	private final int minZ;
	private final int side;
	private final long total;
	private long next = 0;
	private long checked = 0;
	private int found = 0;
	private final Deque<Read> reads = new ArrayDeque<>();
	private final ServerBossEvent bar;
	private final boolean relightOnly;

	private AreaJob(CommandSourceStack src, int side, boolean relightOnly) {
		this.relightOnly = relightOnly;
		this.server = src.getServer();
		this.level = src.getLevel();
		ChunkPos center = new ChunkPos(net.minecraft.core.BlockPos.containing(src.getPosition()));
		this.side = side;
		this.minX = center.x - side / 2;
		this.minZ = center.z - side / 2;
		this.total = (long) side * side;
		this.bar = new ServerBossEvent(Component.literal("Retrogen: 0%"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
		ServerPlayer player = src.getPlayer();
		if (player != null) {
			bar.addPlayer(player);
		}
	}

	public static String start(CommandSourceStack src, int side, boolean relightOnly) {
		if (current != null) {
			return "Ya hay una tarea en curso. Usa /retrogen cancel para detenerla.";
		}
		current = new AreaJob(src, side, relightOnly);
		RetrogenMods.LOGGER.info("Tarea de área iniciada: {}x{} chunks ({} en total)", side, side, current.total);
		if (relightOnly) {
			return "Relight iniciado: " + side + "x" + side + " chunks. Al terminar, guarda y sal del mundo y vuelve a entrar para que se recalcule la luz.";
		}
		return "Tarea iniciada: " + side + "x" + side + " chunks. Solo se procesan los chunks que ya existen. Progreso en la barra superior.";
	}

	public static boolean cancel() {
		if (current == null) {
			return false;
		}
		current.finish("Tarea cancelada");
		return true;
	}

	public static void tick(int perTick) {
		if (current != null) {
			current.step(perTick);
		}
	}

	private void step(int perTick) {
		// Pide leer del disco unos cuantos chunks por adelantado (en otro hilo).
		while (reads.size() < READ_AHEAD && next < total) {
			ChunkPos pos = new ChunkPos(minX + (int) (next % side), minZ + (int) (next / side));
			next++;
			reads.add(new Read(pos, level.getChunkSource().chunkMap.read(pos)));
		}

		int budget = perTick;
		int guard = 4000;
		while (!reads.isEmpty() && reads.peek().future().isDone() && budget > 0 && guard-- > 0) {
			Read r = reads.poll();
			checked++;
			Optional<CompoundTag> tag = r.future().isCompletedExceptionally() ? Optional.empty() : r.future().getNow(Optional.empty());
			if (tag.isPresent()) {
				try {
					LevelChunk chunk = level.getChunk(r.pos().x, r.pos().z);
					if (relightOnly) {
						chunk.setLightCorrect(false);
						chunk.markUnsaved();
					} else {
						RetrogenMods.processChunk(level, chunk);
					}
					found++;
				} catch (Exception e) {
					RetrogenMods.LOGGER.warn("Falló el chunk {} en la tarea de área", r.pos(), e);
				}
				budget--;
			}
		}

		float progress = Math.min(1f, checked / (float) total);
		bar.setProgress(progress);
		bar.setName(Component.literal("Retrogen: " + (int) (progress * 100) + "%  (" + checked + "/" + total + ", existentes: " + found + ")"));
		if (checked >= total) {
			finish("Tarea terminada");
		}
	}

	private void finish(String title) {
		bar.removeAllPlayers();
		String msg = "Retrogen: " + title + ". Revisados " + checked + " de " + total + " chunks, procesados " + found + " existentes.";
		RetrogenMods.LOGGER.info(msg);
		server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
		current = null;
	}
}

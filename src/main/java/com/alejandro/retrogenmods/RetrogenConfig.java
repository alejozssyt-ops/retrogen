package com.alejandro.retrogenmods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuración en config/retrogen_mods.json (se crea sola la primera vez).
 */
public class RetrogenConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Namespaces (ids de mod) cuyas features/estructuras se aplican retroactivamente. */
	public List<String> namespaces = new ArrayList<>(List.of("mss", "mes", "ecologics"));

	/** Cuántos chunks se procesan por tick (más alto = más rápido pero más lag). */
	public int chunksPerTick = 1;

	public boolean retrogenStructures = true;
	public boolean retrogenFeatures = true;

	/** Súbelo (2, 3...) para volver a aplicar retrogen en todos los chunks, por ejemplo tras añadir un mod nuevo. */
	public int version = 1;

	/**
	 * Las estructuras no se retrogeneran en chunks donde algún jugador pasó más de estos ticks
	 * (20 ticks = 1 segundo; 1200 = 1 minuto). Pon -1 para desactivar esta protección.
	 */
	public long skipStructuresIfInhabitedTicksAbove = 1200;

	/** Dimensiones donde NO se aplica ninguna protección (ni zonas protegidas ni tiempo habitado): todo se retrogenera. */
	public List<String> unprotectedDimensions = new ArrayList<>(List.of("minecraft:the_end"));

	/** Zonas donde el mod no toca nada (ni estructuras ni features). */
	public List<ProtectedZone> protectedZones = new ArrayList<>(List.of(new ProtectedZone(850, -750, 16, "minecraft:overworld")));

	public static class ProtectedZone {
		/** Centro de la zona, en coordenadas de bloque. */
		public int blockX;
		public int blockZ;
		/** Diámetro de la zona en chunks (8 = 128 bloques de lado, centrado en blockX/blockZ). */
		public int diameterChunks;
		public String dimension = "minecraft:overworld";

		public ProtectedZone() {
		}

		public ProtectedZone(int blockX, int blockZ, int diameterChunks, String dimension) {
			this.blockX = blockX;
			this.blockZ = blockZ;
			this.diameterChunks = diameterChunks;
			this.dimension = dimension;
		}
	}

	public static RetrogenConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("retrogen_mods.json");
		RetrogenConfig config = new RetrogenConfig();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				RetrogenConfig loaded = GSON.fromJson(reader, RetrogenConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				RetrogenMods.LOGGER.error("No se pudo leer {}, usando valores por defecto", path, e);
			}
		}
		if (config.namespaces == null) {
			config.namespaces = new ArrayList<>();
		}
		config.chunksPerTick = Math.max(1, config.chunksPerTick);
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			RetrogenMods.LOGGER.error("No se pudo escribir {}", path, e);
		}
		return config;
	}
}

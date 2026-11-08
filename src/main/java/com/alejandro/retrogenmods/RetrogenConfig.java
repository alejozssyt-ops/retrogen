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
	public List<String> namespaces = new ArrayList<>(List.of("ecologics", "moogs_soaring_structures"));

	/** Cuántos chunks se procesan por tick (más alto = más rápido pero más lag). */
	public int chunksPerTick = 1;

	public boolean retrogenStructures = true;
	public boolean retrogenFeatures = true;

	/** Súbelo (2, 3...) para volver a aplicar retrogen en todos los chunks, por ejemplo tras añadir un mod nuevo. */
	public int version = 1;

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

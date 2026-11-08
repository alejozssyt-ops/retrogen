package com.alejandro.retrogenmods;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Guarda dentro de cada chunk (en disco) qué namespaces ya recibieron retrogen,
 * para no aplicarlo dos veces.
 */
public final class ChunkMarks {
	private ChunkMarks() {
	}

	public static final AttachmentType<List<String>> APPLIED = AttachmentRegistry.createPersistent(
			Identifier.fromNamespaceAndPath("retrogen_mods", "applied"),
			Codec.STRING.listOf()
	);

	public static void init() {
		// Fuerza la carga de la clase (registra el attachment).
	}

	public static String key(String namespace, int version) {
		return namespace + "@" + version;
	}

	public static boolean isApplied(ChunkAccess chunk, String key) {
		List<String> list = chunk.getAttached(APPLIED);
		return list != null && list.contains(key);
	}

	public static void markApplied(ChunkAccess chunk, String key) {
		List<String> current = chunk.getAttached(APPLIED);
		List<String> updated = current == null ? new ArrayList<>() : new ArrayList<>(current);
		if (!updated.contains(key)) {
			updated.add(key);
			chunk.setAttached(APPLIED, updated);
		}
	}
}

package dev.engine_room.flywheel.backend.engine.direct;

import dev.engine_room.flywheel.backend.engine.CpuArena;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;

/** CPU transform storage for the direct renderer's embedded environments. */
public final class DirectEnvironmentStorage {
	public static final int MATRIX_SIZE_BYTES = (16 + 12) * Float.BYTES;

	private final Object lock = new Object();
	private final ReferenceSet<DirectEmbeddedEnvironment> environments = new ReferenceLinkedOpenHashSet<>();
	private final CpuArena arena = new CpuArena(MATRIX_SIZE_BYTES, 32);

	public DirectEnvironmentStorage() {
		// Matrix slot zero is the world/identity transform.
		arena.alloc();
	}

	public void track(DirectEmbeddedEnvironment environment) {
		synchronized (lock) {
			if (environments.add(environment)) {
				environment.setMatrixIndex(arena.alloc());
			}
		}
	}

	/** Materialize every live embedded transform into the upload arena. */
	public void flush() {
		environments.removeIf(environment -> {
			if (!environment.isDeleted()) {
				return false;
			}
			int index = environment.matrixIndex();
			if (index > 0) {
				arena.free(index);
			}
			return true;
		});
		for (DirectEmbeddedEnvironment environment : environments) {
			environment.flush(arena.indexToPointer(environment.matrixIndex()));
		}
	}

	public CpuArena arena() {
		return arena;
	}

	public void delete() {
		environments.clear();
		arena.delete();
	}
}

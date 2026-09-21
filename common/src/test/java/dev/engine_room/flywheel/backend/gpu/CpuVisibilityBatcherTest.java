package dev.engine_room.flywheel.backend.gpu;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CpuVisibilityBatcherTest {
	private static final CpuVisibilityBatcher.Bounds NEAR = new CpuVisibilityBatcher.Bounds(0, 0, 0, 1, 1, 1);
	private static final CpuVisibilityBatcher.Bounds FAR = new CpuVisibilityBatcher.Bounds(32, 0, 0, 33, 1, 1);

	@Test
	void cullingCreatesStableNonEmptyBatches() {
		var batcher = new CpuVisibilityBatcher<String, Integer>();
		batcher.add("solid", NEAR, 1);
		batcher.add("translucent", NEAR, 2);
		batcher.add("solid", FAR, 3);
		batcher.add("solid", NEAR, 4);

		var batches = batcher.cull(bounds -> bounds == NEAR);

		Assertions.assertEquals(List.of(
				new CpuVisibilityBatcher.Batch<>("solid", List.of(1, 4)),
				new CpuVisibilityBatcher.Batch<>("translucent", List.of(2))), batches);
	}

	@Test
	void invisibleEntriesDoNotCreateBatches() {
		var batcher = new CpuVisibilityBatcher<String, Integer>();
		batcher.add("solid", FAR, 1);

		Assertions.assertTrue(batcher.cull(bounds -> false).isEmpty());
	}

	@Test
	void boundsRejectInvalidCoordinates() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new CpuVisibilityBatcher.Bounds(1, 0, 0, 0, 1, 1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new CpuVisibilityBatcher.Bounds(Double.NaN, 0, 0, 1, 1, 1));
	}
}

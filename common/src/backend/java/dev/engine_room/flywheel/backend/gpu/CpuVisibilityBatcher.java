package dev.engine_room.flywheel.backend.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import org.joml.FrustumIntersection;

import net.minecraft.core.Vec3i;

/**
 * CPU frustum culling and stable draw batching for the 26.2 renderer.
 *
 * <p>The old indirect backend compacted visible instance indices in a compute
 * shader. Minecraft's public 26.2 GPU API deliberately does not expose that
 * facility, so the replacement needs a small, explicit CPU-side boundary. A
 * caller records one entry for every drawable, then calls {@link #cull} once
 * per frame. The result preserves insertion order both between and within draw
 * groups, which makes it safe to feed directly into ordered material passes.</p>
 *
 * <p>This class owns no GPU resources and has no dependency on legacy OpenGL
 * state. The draw key should describe resources that must be shared by one
 * submission (for example pipeline, mesh and material); the value is normally
 * an instance index or a CPU instance record.</p>
 */
public final class CpuVisibilityBatcher<K, T> {
	private final List<Entry<K, T>> entries = new ArrayList<>();

	/** Add a drawable with bounds expressed in world coordinates. */
	public void add(K key, Bounds bounds, T value) {
		entries.add(new Entry<>(Objects.requireNonNull(key, "key"), Objects.requireNonNull(bounds, "bounds"), value));
	}

	/** Remove every recorded drawable. */
	public void clear() {
		entries.clear();
	}

	public int size() {
		return entries.size();
	}

	/**
	 * Cull entries against Minecraft's current camera-relative frustum.
	 *
	 * @param renderOrigin the world-space origin subtracted from model data for
	 *                     the current render frame
	 */
	public List<Batch<K, T>> cull(FrustumIntersection frustum, Vec3i renderOrigin) {
		Objects.requireNonNull(frustum, "frustum");
		Objects.requireNonNull(renderOrigin, "renderOrigin");
		return cull(bounds -> bounds.test(frustum, renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ()));
	}

	/**
	 * Cull entries using a custom visibility test. This is useful for distance,
	 * section, or occlusion policies layered on top of frustum culling, and
	 * keeps the batching contract independently testable.
	 */
	public List<Batch<K, T>> cull(VisibilityTest visibility) {
		Objects.requireNonNull(visibility, "visibility");

		LinkedHashMap<K, List<T>> visible = new LinkedHashMap<>();
		for (Entry<K, T> entry : entries) {
			if (visibility.isVisible(entry.bounds)) {
				visible.computeIfAbsent(entry.key, ignored -> new ArrayList<>()).add(entry.value);
			}
		}

		List<Batch<K, T>> out = new ArrayList<>(visible.size());
		for (var entry : visible.entrySet()) {
			out.add(new Batch<>(entry.getKey(), entry.getValue()));
		}
		return List.copyOf(out);
	}

	@FunctionalInterface
	public interface VisibilityTest {
		boolean isVisible(Bounds bounds);
	}

	/** An axis-aligned world-space bounding box. */
	public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		public Bounds {
			if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
					|| !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)) {
				throw new IllegalArgumentException("Visibility bounds must be finite");
			}
			if (minX > maxX || minY > maxY || minZ > maxZ) {
				throw new IllegalArgumentException("Visibility bounds minimum must not exceed maximum");
			}
		}

		public boolean test(FrustumIntersection frustum, int originX, int originY, int originZ) {
			return frustum.testAab((float) (minX - originX), (float) (minY - originY), (float) (minZ - originZ),
					(float) (maxX - originX), (float) (maxY - originY), (float) (maxZ - originZ));
		}
	}

	/** A non-empty ordered set of visible values that can share one submission. */
	public record Batch<K, T>(K key, List<T> values) {
		public Batch {
			Objects.requireNonNull(key, "key");
			values = List.copyOf(values);
			if (values.isEmpty()) {
				throw new IllegalArgumentException("A visible batch cannot be empty");
			}
		}
	}

	private record Entry<K, T>(K key, Bounds bounds, T value) {
	}
}

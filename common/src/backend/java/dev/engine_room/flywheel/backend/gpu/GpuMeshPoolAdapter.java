package dev.engine_room.flywheel.backend.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.blaze3d.IndexType;

import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.VertexView;

/**
 * Rebuilds a legacy {@link MeshPool}'s CPU meshes into public 26.2 GPU streams.
 *
 * <p>The old pool owns OpenGL buffers, which cannot be used by a 26.2 render
 * pass. This adapter deliberately writes the original {@link Mesh} objects
 * again, preserving the pool's shared {@link IndexSequence} arrangement and
 * producing the offsets required by {@link GpuInstancedDrawPlan}. Upload it
 * before opening a render pass, then obtain each live pooled mesh's geometry
 * through {@link #geometry(MeshPool.PooledMesh)}.</p>
 */
public final class GpuMeshPoolAdapter implements AutoCloseable {
	private final GpuVertexBuffer vertices;
	private final GpuIndexBuffer indices;
	private final VertexView vertexView = InternalVertex.createVertexView();
	private final Map<MeshPool.PooledMesh, Geometry> geometry = new IdentityHashMap<>();
	private final Map<Mesh, Geometry> sourceGeometry = new IdentityHashMap<>();
	private boolean closed;

	public GpuMeshPoolAdapter(Supplier<String> label) {
		Objects.requireNonNull(label, "label");
		vertices = new GpuVertexBuffer(() -> label.get() + " vertices");
		indices = new GpuIndexBuffer(() -> label.get() + " indices", IndexType.INT);
	}

	/** Recreate both streams from the pool's currently live CPU mesh allocations. */
	public void upload(MeshPool pool) {
		ensureOpen();
		Objects.requireNonNull(pool, "pool");

		List<MeshPool.PooledMesh> meshes = pool.pooledMeshes().stream()
				.filter(mesh -> !mesh.isDeleted() && mesh.vertexCount() > 0 && mesh.indexCount() > 0)
				.toList();
		long vertexBytes = 0;
		long indexCount = 0;
		Map<IndexSequence, Integer> sequenceCounts = new IdentityHashMap<>();
		List<IndexSequence> sequences = new ArrayList<>();
		for (MeshPool.PooledMesh pooled : meshes) {
			vertexBytes = Math.addExact(vertexBytes, pooled.byteSize());
			IndexSequence sequence = pooled.mesh().indexSequence();
			Integer oldCount = sequenceCounts.get(sequence);
			if (oldCount == null) {
				sequenceCounts.put(sequence, pooled.indexCount());
				sequences.add(sequence);
				indexCount = Math.addExact(indexCount, pooled.indexCount());
			} else if (pooled.indexCount() > oldCount) {
				sequenceCounts.put(sequence, pooled.indexCount());
				indexCount = Math.addExact(indexCount, pooled.indexCount() - oldCount);
			}
		}

		if (vertexBytes == 0 || indexCount == 0) {
			geometry.clear();
			return;
		}
		long indexBytes = Math.multiplyExact(indexCount, Integer.BYTES);
		if (vertexBytes > Integer.MAX_VALUE || indexBytes > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Mesh pool is too large for a CPU upload buffer");
		}

		MemoryBlock vertexBlock = MemoryBlock.malloc(vertexBytes);
		MemoryBlock indexBlock = MemoryBlock.malloc(indexBytes);
		try {
			Map<IndexSequence, Integer> firstIndices = new IdentityHashMap<>();
			int nextIndex = 0;
			for (IndexSequence sequence : sequences) {
				firstIndices.put(sequence, nextIndex);
				int count = sequenceCounts.get(sequence);
				sequence.fill(indexBlock.ptr() + (long) nextIndex * Integer.BYTES, count);
				nextIndex += count;
			}

			Map<MeshPool.PooledMesh, Geometry> rebuilt = new IdentityHashMap<>();
			long vertexOffset = 0;
			int baseVertex = 0;
			for (MeshPool.PooledMesh pooled : meshes) {
				vertexView.ptr(vertexBlock.ptr() + vertexOffset);
				vertexView.vertexCount(pooled.vertexCount());
				pooled.mesh().write(vertexView);
				rebuilt.put(pooled, new Geometry(firstIndices.get(pooled.mesh().indexSequence()), baseVertex,
						pooled.indexCount(), pooled.vertexCount()));
				vertexOffset += pooled.byteSize();
				baseVertex += pooled.vertexCount();
			}

			vertices.upload(uploadView(vertexBlock, vertexBytes));
			indices.upload(uploadView(indexBlock, indexBytes));
			geometry.clear();
			geometry.putAll(rebuilt);
		} finally {
			vertexBlock.free();
			indexBlock.free();
		}
	}

	/** Returns GPU draw offsets for a mesh present in the most recent upload. */
	public Optional<Geometry> geometry(MeshPool.PooledMesh pooledMesh) {
		return Optional.ofNullable(geometry.get(Objects.requireNonNull(pooledMesh, "pooledMesh")));
	}

	/**
	 * Upload direct-renderer meshes without constructing the legacy GL-backed
	 * {@link MeshPool}. The list is de-duplicated by identity, preserving the
	 * same sharing rule as the old pool while keeping this path wholly public-GPU.
	 */
	public void uploadMeshes(List<Mesh> input) {
		ensureOpen();
		Objects.requireNonNull(input, "input");
		List<Mesh> meshes = new ArrayList<>();
		Map<Mesh, Boolean> seen = new IdentityHashMap<>();
		for (Mesh mesh : input) {
			Objects.requireNonNull(mesh, "mesh");
			if (mesh.vertexCount() > 0 && mesh.indexCount() > 0 && seen.put(mesh, Boolean.TRUE) == null) meshes.add(mesh);
		}
		long vertexBytes = 0;
		long indexCount = 0;
		Map<IndexSequence, Integer> sequenceCounts = new IdentityHashMap<>();
		List<IndexSequence> sequences = new ArrayList<>();
		for (Mesh mesh : meshes) {
			vertexBytes = Math.addExact(vertexBytes, (long) mesh.vertexCount() * InternalVertex.STRIDE);
			Integer oldCount = sequenceCounts.get(mesh.indexSequence());
			if (oldCount == null) {
				sequenceCounts.put(mesh.indexSequence(), mesh.indexCount());
				sequences.add(mesh.indexSequence());
				indexCount = Math.addExact(indexCount, mesh.indexCount());
			} else if (mesh.indexCount() > oldCount) {
				sequenceCounts.put(mesh.indexSequence(), mesh.indexCount());
				indexCount = Math.addExact(indexCount, mesh.indexCount() - oldCount);
			}
		}
		if (vertexBytes == 0 || indexCount == 0) {
			sourceGeometry.clear();
			return;
		}
		long indexBytes = Math.multiplyExact(indexCount, Integer.BYTES);
		if (vertexBytes > Integer.MAX_VALUE || indexBytes > Integer.MAX_VALUE) throw new IllegalArgumentException("Direct mesh upload is too large for a CPU buffer");
		MemoryBlock vertexBlock = MemoryBlock.malloc(vertexBytes);
		MemoryBlock indexBlock = MemoryBlock.malloc(indexBytes);
		try {
			Map<IndexSequence, Integer> firstIndices = new IdentityHashMap<>();
			int nextIndex = 0;
			for (IndexSequence sequence : sequences) {
				firstIndices.put(sequence, nextIndex);
				int count = sequenceCounts.get(sequence);
				sequence.fill(indexBlock.ptr() + (long) nextIndex * Integer.BYTES, count);
				nextIndex += count;
			}
			Map<Mesh, Geometry> rebuilt = new IdentityHashMap<>();
			long vertexOffset = 0;
			int baseVertex = 0;
			for (Mesh mesh : meshes) {
				vertexView.ptr(vertexBlock.ptr() + vertexOffset);
				vertexView.vertexCount(mesh.vertexCount());
				mesh.write(vertexView);
				rebuilt.put(mesh, new Geometry(firstIndices.get(mesh.indexSequence()), baseVertex, mesh.indexCount(), mesh.vertexCount()));
				vertexOffset += (long) mesh.vertexCount() * InternalVertex.STRIDE;
				baseVertex += mesh.vertexCount();
			}
			vertices.upload(uploadView(vertexBlock, vertexBytes));
			indices.upload(uploadView(indexBlock, indexBytes));
			sourceGeometry.clear();
			sourceGeometry.putAll(rebuilt);
		} finally {
			vertexBlock.free();
			indexBlock.free();
		}
	}

	/** Geometry for a source mesh uploaded through {@link #uploadMeshes(List)}. */
	public Optional<Geometry> geometry(Mesh mesh) {
		return Optional.ofNullable(sourceGeometry.get(Objects.requireNonNull(mesh, "mesh")));
	}

	public GpuVertexBuffer vertices() {
		return vertices;
	}

	public GpuIndexBuffer indices() {
		return indices;
	}

	private static ByteBuffer uploadView(MemoryBlock block, long size) {
		ByteBuffer view = block.asBuffer();
		view.position(0);
		view.limit(Math.toIntExact(size));
		return view;
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("Mesh pool adapter has been closed");
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		geometry.clear();
		sourceGeometry.clear();
		vertices.close();
		indices.close();
	}

	/** The indexed geometry range required for one pooled mesh draw. */
	public record Geometry(int firstIndex, int baseVertex, int indexCount, int vertexCount) {
	}
}

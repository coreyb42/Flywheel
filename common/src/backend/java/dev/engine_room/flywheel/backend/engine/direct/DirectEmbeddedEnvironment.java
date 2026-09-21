package dev.engine_room.flywheel.backend.engine.direct;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.core.Vec3i;

/** An embedded transform hierarchy that contains no legacy shader interaction. */
public final class DirectEmbeddedEnvironment implements VisualEmbedding, DirectEnvironment {
	private final DirectEngine engine;
	private final Vec3i renderOrigin;
	@Nullable
	private final DirectEmbeddedEnvironment parent;
	private final InstancerProvider instancerProvider = new InstancerProvider() {
		@Override
		public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
			return engine.instancer(DirectEmbeddedEnvironment.this, type, model, bias);
		}
	};
	private final Matrix4f pose = new Matrix4f();
	private final Matrix3f normal = new Matrix3f();
	private final Matrix4f composedPose = new Matrix4f();
	private final Matrix3f composedNormal = new Matrix3f();
	private int matrixIndex;
	private boolean deleted;

	DirectEmbeddedEnvironment(DirectEngine engine, Vec3i renderOrigin, @Nullable DirectEmbeddedEnvironment parent) {
		this.engine = engine;
		this.renderOrigin = renderOrigin;
		this.parent = parent;
	}

	@Override
	public void transforms(Matrix4fc pose, Matrix3fc normal) {
		this.pose.set(pose);
		this.normal.set(normal);
	}

	@Override
	public InstancerProvider instancerProvider() {
		return instancerProvider;
	}

	@Override
	public Vec3i renderOrigin() {
		return renderOrigin;
	}

	@Override
	public VisualEmbedding createEmbedding(Vec3i renderOrigin) {
		var child = new DirectEmbeddedEnvironment(engine, renderOrigin, this);
		engine.environmentStorage().track(child);
		return child;
	}

	@Override
	public DrawContext drawContext() {
		return DrawContext.EMBEDDED;
	}

	@Override
	public int matrixIndex() {
		return matrixIndex;
	}

	void setMatrixIndex(int matrixIndex) {
		this.matrixIndex = matrixIndex;
	}

	void flush(long pointer) {
		composedPose.identity();
		composedNormal.identity();
		compose(composedPose, composedNormal);
		ExtraMemoryOps.putMatrix4f(pointer, composedPose);
		ExtraMemoryOps.putMatrix3fPadded(pointer + 16L * Float.BYTES, composedNormal);
	}

	private void compose(Matrix4f outPose, Matrix3f outNormal) {
		if (parent != null) {
			parent.compose(outPose, outNormal);
			outPose.mul(pose);
			outNormal.mul(normal);
		} else {
			outPose.set(pose);
			outNormal.set(normal);
		}
	}

	@Override
	public void delete() {
		deleted = true;
	}

	boolean isDeleted() {
		return deleted;
	}
}

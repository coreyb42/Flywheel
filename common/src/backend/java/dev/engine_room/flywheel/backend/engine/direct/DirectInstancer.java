package dev.engine_room.flywheel.backend.engine.direct;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;

/**
 * CPU-owned instance collection for the public GPU renderer.
 *
 * <p>This intentionally does not inherit the old {@code BaseInstancer}: that
 * class is coupled to an {@code Environment} and the retired GL buffer update
 * lifecycle. A direct instancer retains the API's visibility/ownership
 * behaviour and serializes its native layout only when the direct manager is
 * preparing an upload.</p>
 */
final class DirectInstancer<I extends Instance> implements Instancer<I> {
	private final Object lock = new Object();
	private final InstanceType<I> type;
	private final ArrayList<I> instances = new ArrayList<>();
	private final ArrayList<Handle<I>> handles = new ArrayList<>();

	DirectInstancer(InstanceType<I> type) {
		this.type = type;
	}

	@Override
	public I createInstance() {
		synchronized (lock) {
			Handle<I> handle = new Handle<>(this, instances.size());
			I instance = type.create(handle);
			instances.add(instance);
			handles.add(handle);
			return instance;
		}
	}

	@Override
	public void stealInstance(@Nullable I instance) {
		if (instance == null || !(instance.handle() instanceof Handle<?> rawHandle)) {
			return;
		}
		@SuppressWarnings("unchecked")
		Handle<I> handle = (Handle<I>) rawHandle;
		if (handle.owner == this || !handle.visible) {
			return;
		}
		DirectInstancer<I> oldOwner = handle.owner;
		// Lock ordering by identity keeps cross-instancer stealing deadlock-free.
		DirectInstancer<I> first = System.identityHashCode(oldOwner) < System.identityHashCode(this) ? oldOwner : this;
		DirectInstancer<I> second = first == oldOwner ? this : oldOwner;
		synchronized (first.lock) {
			synchronized (second.lock) {
				if (handle.owner != oldOwner || !handle.visible) return;
				oldOwner.remove(handle.index);
				handle.owner = this;
				handle.index = instances.size();
				instances.add(instance);
				handles.add(handle);
			}
		}
	}

	int instanceCount() {
		synchronized (lock) {
			return instances.size();
		}
	}

	int stride() {
		return type.layout().byteSize();
	}

	/** Serialize a stable snapshot for an instance-rate vertex binding. */
	int writeInstances(MemoryBlock destination) {
		synchronized (lock) {
			long required = Math.multiplyExact((long) stride(), instances.size());
			if (destination.size() < required) {
				throw new IllegalArgumentException("Direct instance destination is too small");
			}
			long pointer = destination.ptr();
			for (I instance : instances) {
				type.writer().write(pointer, instance);
				pointer += stride();
			}
			return instances.size();
		}
	}

	private void remove(int index) {
		int last = instances.size() - 1;
		Handle<I> removed = handles.get(index);
		if (index != last) {
			I movedInstance = instances.get(last);
			Handle<I> movedHandle = handles.get(last);
			instances.set(index, movedInstance);
			handles.set(index, movedHandle);
			movedHandle.index = index;
		}
		instances.remove(last);
		handles.remove(last);
		removed.index = -1;
	}

	private static final class Handle<I extends Instance> implements InstanceHandle {
		private DirectInstancer<I> owner;
		private int index;
		private boolean visible = true;

		private Handle(DirectInstancer<I> owner, int index) {
			this.owner = owner;
			this.index = index;
		}

		@Override
		public void setChanged() {
			// Direct uploads use a complete synchronized snapshot. There is no GL
			// subrange to update and no stale data can be observed between frames.
		}

		@Override
		public void setDeleted() {
			setVisible(false);
		}

		@Override
		public void setVisible(boolean visible) {
			if (this.visible == visible) return;
			synchronized (owner.lock) {
				if (this.visible == visible) return;
				if (!visible && index >= 0) owner.remove(index);
				this.visible = visible;
			}
		}

		@Override
		public boolean isVisible() {
			return visible;
		}
	}
}

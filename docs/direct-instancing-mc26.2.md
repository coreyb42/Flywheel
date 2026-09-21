# Direct instancing: Minecraft 26.2

`GpuDirectRenderPassManager` is Flywheel's first production submission path for
the 26.2 public renderer. It accepts CPU-authored `GpuInstancedDrawPlan`s and a
live Minecraft `RenderTarget`, then performs the following in one owned command
encoder:

1. uploads each dirty named uniform before a render pass is opened;
2. opens a pass on the target's public colour and (when present) depth views,
   without clearing either attachment;
3. binds the plan's immutable pipeline, textures, uniforms, vertex streams and
   index stream; and
4. emits a real indexed instanced draw for every plan before closing the pass.

This is intentionally a direct, CPU-batched path. It does not depend on the
removed GL state APIs or claim to provide the previous compute-driven culling.
Visibility construction, mesh/instance upload, and pipeline compilation remain
upstream responsibilities; invalid or empty batches are rejected rather than
silently rendered as no-ops.

The manager must run on the render thread while the supplied target's texture
views are live. It preserves the target's contents, so its caller determines
the correct world-render stage and uses a colour/depth compatible pipeline.

# OIT target and pass design: Minecraft 26.2

## Verified API constraints

This design was checked against the NeoForge `26.2.0.88` transformed source.

- `GpuDevice.createTexture()` rejects every non-cubemap texture where
  `depthOrLayers > 1`; it also rejects a render-pass attachment whose texture
  has more than one layer. The legacy four-layer coefficient array is therefore
  not a possible 26.2 attachment.
- `RenderPassDescriptor` accepts multiple color attachments. `RenderPass` checks
  that all attachments have identical dimensions and that their textures have
  `GpuTexture.USAGE_RENDER_ATTACHMENT`.
- `RenderPipeline.Builder` has eight color-target slots; `ColorTargetState`
  accepts a distinct `BlendFunction` and format for each slot. Four coefficient
  attachments are consequently supported by the public API.
- A texture sampled by a later pass requires `USAGE_TEXTURE_BINDING`. A
  `GpuTextureView` owns its view separately from its `GpuTexture`, so both must
  be closed, view first.
- An encoder permits one open pass at a time. Upload/copy operations must occur
  outside it; sequential OIT passes are correct as long as each is closed before
  the next begins.

`OitTargets` implements only the resulting resource ownership. It creates six
single-layer 2D targets of a common size:

| Target | Format | Purpose |
| --- | --- | --- |
| depth bounds | `RG32_FLOAT` | Min/max fragment depth reduction. |
| coefficient 0–3 | `RGBA16_FLOAT` | The four former array layers. |
| accumulation | `RGBA16_FLOAT` | Evaluated transparent color/weight. |

All use `USAGE_RENDER_ATTACHMENT | USAGE_TEXTURE_BINDING`, a single mip, and a
single layer. Resize is transactional: any failed allocation closes all already
created views and textures, leaving the owner unallocated.

## Required pass sequence

The eventual GPU OIT manager must create immutable pipelines and passes in this
order; it must not emulate the old FBO by binding an ID or mutate global GL
state.

1. **Depth range.** Create a pass whose sole color attachment is depth-bounds,
   cleared to `(-far, -far, 0, 0)`. Use an additive `MAX` blend pipeline with
   depth testing enabled and depth writes disabled. Draw OIT geometry.
2. **Coefficient generation.** Create one pass with coefficient views 0–3 as
   four color attachments, all cleared to zero. The pipeline must declare four
   matching `RGBA16_FLOAT` target states and additive blending. Draw the same
   OIT geometry with the coefficient-generating fragment variant.
3. **Depth reconstruction.** Render the full-screen primitive with the depth
   reconstruction pipeline. Bind all coefficient views and the depth-bounds
   view as named textures. The pass must attach the selected scene depth view,
   have no color attachment, and use a depth-write/always-depth-test state.
   This depends on confirming that the chosen scene depth target may be
   attached at this stage; use the main/item-entity target's public view, never
   a depth texture ID.
4. **Accumulation.** Create a pass with accumulation as its sole color target,
   cleared to zero. Bind coefficient and depth-bounds views, use additive
   blending and depth testing with writes disabled, then draw OIT geometry with
   the evaluation fragment variant.
5. **Composite.** Create a pass targeting the selected public scene color and
   depth views. Bind accumulation and coefficient/depth views; the composite
   pipeline must use the old separate alpha blend equation expressed as a
   `BlendFunction`, write depth, and draw a full-screen primitive. The pipeline
   formats must exactly match the selected scene views.

The OIT shader interface must change from one `sampler2DArray` plus layer
indices to four named `sampler2D` bindings. That is a source-level shader and
pipeline migration, not a resource-class concern.

## Integration gates

Before replacing `engine.indirect.OitFramebuffer`, implement the above with a
single CPU/direct-instancing vertical slice and verify:

1. Four color target states can be precompiled on the supported device.
2. All five passes close before the next begins, including on shader failure.
3. Resize, world leave, renderer reload, and device teardown close every view
   before its texture and recreate no stale-size attachments.
4. Render both Fabulous and non-Fabulous scene target paths, because their
   public color/depth views may differ.
5. Compare overlapping translucent Create models with ordinary translucency and
   confirm depth writes/compositing preserve the intended ordering.

Until these gates pass, select the normal translucent material path rather than
exposing incomplete OIT.

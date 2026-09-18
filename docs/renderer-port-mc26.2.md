# Flywheel renderer port: Minecraft 26.2

## Decision

Port Flywheel to the supported 26.2 GPU abstraction. Do not retain a parallel
OpenGL backend, obtain native handles from Minecraft GPU resources, or use
reflection/mixins to recover removed IDs. Those approaches would tie the mod to
one renderer backend and are incompatible with the renderer's resource lifetime
and command submission model.

The old renderer is split into two implementation strategies:

- **Instancing** uses VAOs, texture buffers, user-compiled GL programs, and
  stateful OpenGL calls. Its replacement can use `GpuBuffer` vertex bindings,
  a `RenderPipeline`, `RenderPass`, texture views/samplers, and CPU-managed
  uploads.
- **Indirect** uses compute shaders and shader-storage buffers for culling,
  instance data, and GPU-generated draw commands. The 26.2 public API exposes
  neither compute passes nor storage-buffer bindings. It cannot be ported
  literally. Its replacement must perform culling and draw-command generation
  on the CPU, then issue supported direct or indirect render-pass draws.

This preserves Flywheel's supported visuals and fallbacks without relying on
private renderer implementation details.

## Verified 26.2 capabilities

The target source is NeoForge `26.2.0.88` / Java 25.

| Requirement | 26.2 public API | Port consequence |
| --- | --- | --- |
| Vertex and index data | `GpuBuffer` with `USAGE_VERTEX` / `USAGE_INDEX`; `RenderPass.setVertexBuffer` / `setIndexBuffer` | Replace VAO ownership and GL buffer binding. |
| Per-frame and material data | `GpuBuffer` with `USAGE_UNIFORM`; named `RenderPass.setUniform` | Replace UBO binding-point management. |
| Textures | `GpuTextureView` + `GpuSampler`; named `RenderPass.bindTexture` | Replace texture units and integer texture IDs. |
| Main scene targets | `GameRenderer.mainRenderTarget()` exposes color/depth texture views | Render through command-encoder passes; no framebuffer/depth IDs. |
| Pipeline creation | `RenderPipeline` plus `GpuDevice.precompilePipeline` | Replace program linking and GL state mutation with immutable pipeline descriptions. |
| Indirect drawing | `RenderPass.drawIndexedIndirect` with `USAGE_INDIRECT_PARAMETERS` | CPU may prepare commands; capability is optional per device. |
| Compute / SSBO | Not present in the public `GpuDevice`, `CommandEncoder`, `RenderPass`, or `GpuBuffer.Usage` API | Remove GPU culling/SSBO design; use CPU culling and vertex-instancing fallback. |
| Texture arrays | `GpuDevice.createTexture` currently rejects `depthOrLayers > 1` except cubemaps | Split OIT coefficient layers into distinct 2D textures, or redesign the algorithm. |

## Migration order

1. Introduce a `gpu` backend package that owns device resources, resource
   teardown, uploads, and render-pass submission. Keep it separate from the
   existing `gl` package so old and new contracts cannot be accidentally mixed.
2. Port uniform writers from raw UBO IDs to `GpuBuffer` and command-encoder
   uploads. Validate frame, level, fog, player, and embedding data layouts
   against the generated shader declarations.
3. Port mesh pools and direct instancing to vertex/index buffers and a first
   opaque `RenderPipeline`. This is the first required end-to-end vertical
   slice: upload a mesh, bind the main target, bind a real atlas texture view,
   draw instances, and close the pass.
4. Convert material state into immutable pipeline variants: depth test,
   blending, culling, polygon mode, write masks, texture sampler state, and
   shader defines. Cache variants by the existing material key.
5. Replace the shader compiler/linker with `ShaderSource` and
   `GpuDevice.precompilePipeline`, keeping Flywheel's GLSL source generation
   only where it can emit valid 26.2 pipeline shader sources. Fail a pipeline
   cleanly and select the fallback backend on errors.
6. Replace the indirect backend with CPU visibility culling plus direct
   instanced draws. Preserve its public backend selection and performance
   diagnostics, but do not advertise it as GPU-driven culling.
7. Rebuild OIT using command-encoder render passes and separate 2D coefficient
   textures. If the target pipeline API lacks the blend/attachment arrangement
   needed for correctness, keep order-independent materials on the ordinary
   translucent path until a correct implementation is available.
8. Remove the old `gl` backend only after client smoke tests cover opaque,
   translucent, crumbling, light/overlay, embedded rendering, and reloads.

## Acceptance gates

Each phase must compile on Fabric and NeoForge, then pass a development-client
smoke test. The renderer is not considered ported until resource reload,
world join/leave, resize, graphics preset changes, and renderer/device teardown
have been tested. Performance optimizations follow correct resource ownership
and visuals; none may reintroduce direct access to Minecraft's private GPU
objects.

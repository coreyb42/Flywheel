package dev.engine_room.vanillin.visuals;

import org.joml.Matrix4f;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.vanillin.item.ItemModels;
import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.resources.model.BlockStateDefinitions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

public class ItemFrameVisual extends AbstractVisual implements EntityVisual<ItemFrame>, SimpleDynamicVisual {
	public static final RendererReloadCache<BlockState, Model> FRAME_MODELS = new RendererReloadCache<>(state -> new BakedModelBuilder(Minecraft.getInstance()
				.getModelManager()
				.getBlockStateModelSet()
				.get(state))
				.build());

	private final Matrix4f baseTransform = new Matrix4f();

	private final TransformedInstance frame;
	private final TransformedInstance item;
	private final ItemFrame entity;
	private BlockState lastFrameState;
	private ItemStack lastItemStack;

	public ItemFrameVisual(VisualizationContext ctx, ItemFrame entity, float partialTick) {
		super(ctx, entity.level(), partialTick);

		this.entity = entity;

		lastItemStack = entity.getItem()
				.copy();

		lastFrameState = getFrameModelState(entity, lastItemStack);
		var frameModel = FRAME_MODELS.get(lastFrameState);

		frame = ctx.instancerProvider()
				.instancer(InstanceTypes.TRANSFORMED, frameModel)
				.createInstance();

		frame.setTransform(baseTransform);

		item = ctx.instancerProvider()
				.instancer(InstanceTypes.TRANSFORMED, ItemModels.get(level, lastItemStack, ItemDisplayContext.FIXED))
				.createInstance();

		animate(partialTick);
	}

	public static boolean shouldVisualize(ItemFrame entity) {
		// We don't support map rendering, and we can't support exotic item models.
		return !entity.getItem()
				.is(Items.FILLED_MAP) && ItemModels.isSupported(entity.getItem());
	}

	@Override
	public void beginFrame(Context ctx) {
		animate(ctx.partialTick());
	}

	public void animate(float partialTick) {
		var light = LightCoordsUtil.pack(getBlockLightLevel(entity.getPos()), getSkyLightLevel(entity.getPos()));

		boolean invisible = entity.isInvisible();

		Direction direction = entity.getDirection();
		var origin = visualizationContext.renderOrigin();

		float d = 0.46875f;

		float x = (float) (entity.getX() - origin.getX() + direction.getStepX() * d);
		float y = (float) (entity.getY() - origin.getY() + direction.getStepY() * d);
		float z = (float) (entity.getZ() - origin.getZ() + direction.getStepZ() * d);

		baseTransform.translation(x, y, z);
		float xRot = direction.getAxis().isHorizontal() ? 0.0F : -90.0F * direction.getAxisDirection().getStep();
		float yRot = direction.getAxis().isHorizontal() ? 180.0F - direction.toYRot() : 180.0F;
		baseTransform.rotateXYZ(Mth.DEG_TO_RAD * xRot, Mth.DEG_TO_RAD * yRot, 0.0f);

		var stack = entity.getItem();
		var frameState = getFrameModelState(entity, stack);

		if (frameState != lastFrameState) {
			visualizationContext.instancerProvider()
					.instancer(InstanceTypes.TRANSFORMED, FRAME_MODELS.get(frameState))
					.stealInstance(frame);
			lastFrameState = frameState;
		}

		frame.setVisible(!invisible);

		frame.setTransform(baseTransform)
				.translate(-0.5f, -0.5f, -0.5f)
				.light(light)
				.setChanged();

		if (!ItemStack.matches(lastItemStack, stack)) {
			lastItemStack = stack.copy();
			visualizationContext.instancerProvider()
					.instancer(InstanceTypes.TRANSFORMED, ItemModels.get(level, lastItemStack, ItemDisplayContext.FIXED))
					.stealInstance(item);
		}

		item.setTransform(baseTransform);

		if (invisible) {
			item.translate(0.0F, 0.0F, 0.5F);
		} else {
			item.translate(0.0F, 0.0F, 0.4375F);
		}

		int i = entity.hasFramedMap() ? entity.getRotation() % 4 * 2 : entity.getRotation();

		item.rotateZDegrees(i * 360.0F / 8.0F);

		item.scale(0.5F, 0.5F, 0.5F);

		item.light(getLightVal(15728880, light))
				.setChanged();
	}

	@Override
	public void update(float partialTick) {

	}

	@Override
	protected void _delete() {
		frame.delete();
		item.delete();
	}

	private int getLightVal(int glowLightVal, int regularLightVal) {
		return entity.is(EntityTypes.GLOW_ITEM_FRAME) ? glowLightVal : regularLightVal;
	}

	protected int getSkyLightLevel(BlockPos pos) {
		return level.getBrightness(LightLayer.SKY, pos);
	}

	protected int getBlockLightLevelBase(BlockPos pos) {
		return entity.isOnFire() ? 15 : level.getBrightness(LightLayer.BLOCK, pos);
	}

	protected int getBlockLightLevel(BlockPos pos) {
		return entity.is(EntityTypes.GLOW_ITEM_FRAME) ? Math.max(5, getBlockLightLevelBase(pos)) : getBlockLightLevelBase(pos);
	}

	public static BlockState getFrameModelState(ItemFrame entity, ItemStack item) {
		return BlockStateDefinitions.getItemFrameFakeState(entity.is(EntityTypes.GLOW_ITEM_FRAME), item.is(Items.FILLED_MAP));
	}
}

package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.util.InstanceRecycler;
import dev.engine_room.vanillin.item.ItemModels;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;

public class ItemVisual extends AbstractEntityVisual<ItemEntity> implements SimpleDynamicVisual {

	private static final ThreadLocal<RandomSource> RANDOM = ThreadLocal.withInitial(RandomSource::createThreadLocalInstance);

	private final PoseStack pPoseStack = new PoseStack();
	private ItemModels.Geometry geometry;
	private ItemStack currentStack;

	private InstanceRecycler<TransformedInstance> instances;

	public ItemVisual(VisualizationContext ctx, ItemEntity entity, float partialTick) {
		super(ctx, entity, partialTick);

		currentStack = entity.getItem();
		geometry = ItemModels.geometry(currentStack, ItemDisplayContext.GROUND, null);
		var model =  ItemModels.get(level, currentStack, ItemDisplayContext.GROUND);
		instances = new InstanceRecycler<>(() -> ctx.instancerProvider()
				.instancer(InstanceTypes.TRANSFORMED, model)
				.createInstance());

		animate(partialTick);
	}

	public static boolean isSupported(ItemEntity entity) {
		return ItemModels.isSupported(entity.getItem());
	}

	@Override
	public void beginFrame(Context ctx) {
		if (!isVisible(ctx.frustum())) {
			return;
		}

		animate(ctx.partialTick());
	}

	private void animate(float partialTick) {
		pPoseStack.setIdentity();
		TransformStack.of(pPoseStack)
				.translate(getVisualPosition(partialTick));

		ItemStack itemstack = entity.getItem();
		if (!ItemStack.matches(itemstack, currentStack)) {
			instances.delete();
			currentStack = itemstack.copy();
			geometry = ItemModels.geometry(currentStack, ItemDisplayContext.GROUND, null);
			var model =  ItemModels.get(level, currentStack, ItemDisplayContext.GROUND);
			instances = new InstanceRecycler<>(() -> visualizationContext.instancerProvider()
					.instancer(InstanceTypes.TRANSFORMED, model)
					.createInstance());
		}
		instances.resetCount();

		int i = itemstack.isEmpty() ? 187 : Item.getId(itemstack.getItem()) + itemstack.getDamageValue();
		var random = RANDOM.get();
		random.setSeed(i);
		int j = this.getRenderAmount(itemstack);
		float f1 = shouldBob() ? Mth.sin(((float) entity.getAge() + partialTick) / 10.0F + entity.bobOffs) * 0.1F + 0.1F : 0;
		pPoseStack.translate(0.0F, f1 - geometry.minY() + 0.0625F, 0.0F);
		float f3 = ItemEntity.getSpin(entity.getAge() + partialTick, entity.bobOffs);
		pPoseStack.mulPose(Axis.YP.rotation(f3));
		boolean threeDimensional = geometry.zSize() > 0.0625F;
		if (!threeDimensional) {
			pPoseStack.translate(0.0F, 0.0F, -geometry.zSize() * 1.5F * (j - 1) / 2.0F);
		}

		int light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, entity.blockPosition()), level.getBrightness(LightLayer.SKY, entity.blockPosition()));

		for (int k = 0; k < j; ++k) {
			pPoseStack.pushPose();
			if (k > 0) {
				if (threeDimensional) {
					float f11 = (random.nextFloat() * 2.0F - 1.0F) * 0.15F;
					float f13 = (random.nextFloat() * 2.0F - 1.0F) * 0.15F;
					float f10 = (random.nextFloat() * 2.0F - 1.0F) * 0.15F;
					pPoseStack.translate(shouldSpreadItems() ? f11 : 0, shouldSpreadItems() ? f13 : 0, shouldSpreadItems() ? f10 : 0);
				} else {
					float f12 = (random.nextFloat() * 2.0F - 1.0F) * 0.15F * 0.5F;
					float f14 = (random.nextFloat() * 2.0F - 1.0F) * 0.15F * 0.5F;
					pPoseStack.translate(shouldSpreadItems() ? f12 : 0, shouldSpreadItems() ? f14 : 0, 0.0D);
				}
			}

			instances.get()
					.setTransform(pPoseStack.last())
					.light(light)
					.setChanged();
			pPoseStack.popPose();
			if (!threeDimensional) {
				pPoseStack.translate(0.0, 0.0, geometry.zSize() * 1.5F);
			}
		}

		instances.discardExtra();
	}

	protected int getRenderAmount(ItemStack pStack) {
		int i = 1;
		if (pStack.getCount() > 48) {
			i = 5;
		} else if (pStack.getCount() > 32) {
			i = 4;
		} else if (pStack.getCount() > 16) {
			i = 3;
		} else if (pStack.getCount() > 1) {
			i = 2;
		}

		return i;
	}

	/**
	 * @return If items should spread out when rendered in 3D
	 */
	public boolean shouldSpreadItems() {
		return true;
	}

	/**
	 * @return If items should have a bob effect
	 */
	public boolean shouldBob() {
		return true;
	}

	@Override
	protected void _delete() {
		instances.delete();
	}

}

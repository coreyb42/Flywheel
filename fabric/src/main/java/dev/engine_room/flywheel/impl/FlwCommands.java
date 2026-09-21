package dev.engine_room.flywheel.impl;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.LocalCoordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class FlwCommands {
	private FlwCommands() {
	}

	public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext) {
		LiteralArgumentBuilder<FabricClientCommandSource> command = ClientCommands.literal("flywheel");

		command.then(ClientCommands.literal("backend")
				.executes(context -> {
					Backend backend = BackendManager.currentBackend();
					String idStr = Backend.REGISTRY.getIdOrThrow(backend)
							.toString();
					context.getSource().sendFeedback(Component.translatable("command.flywheel.backend.get", idStr));
					return Command.SINGLE_SUCCESS;
				})
				.then(ClientCommands.literal("DEFAULT")
					.executes(context -> {
						FabricFlwConfig.INSTANCE.backend = BackendManager.offBackend();
						FabricFlwConfig.INSTANCE.useDefaultBackend = true;
						FabricFlwConfig.INSTANCE.save();

						// Reload renderers so we can report the actual backend.
						Minecraft.getInstance().levelExtractor.allChanged();

						Backend actualBackend = BackendManager.currentBackend();
						String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
								.toString();
						context.getSource().sendFeedback(Component.translatable("command.flywheel.backend.set", actualIdStr));
						return Command.SINGLE_SUCCESS;
					}))
				.then(ClientCommands.argument("id", BackendArgument.INSTANCE)
					.executes(context -> {
						Backend requestedBackend = context.getArgument("id", Backend.class);
						FabricFlwConfig.INSTANCE.backend = requestedBackend;
						FabricFlwConfig.INSTANCE.useDefaultBackend = false;
						FabricFlwConfig.INSTANCE.save();

						// Reload renderers so we can report the actual backend.
						Minecraft.getInstance().levelExtractor.allChanged();

						Backend actualBackend = BackendManager.currentBackend();
						if (actualBackend != requestedBackend) {
							String requestedIdStr = Backend.REGISTRY.getIdOrThrow(requestedBackend)
									.toString();
							context.getSource().sendError(Component.translatable("command.flywheel.backend.set.unavailable", requestedIdStr));
						}

						String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
								.toString();
						context.getSource().sendFeedback(Component.translatable("command.flywheel.backend.set", actualIdStr));
						return Command.SINGLE_SUCCESS;
					})));

		command.then(ClientCommands.literal("limitUpdates")
				.executes(context -> {
					if (FabricFlwConfig.INSTANCE.limitUpdates) {
						context.getSource().sendFeedback(Component.translatable("command.flywheel.limit_updates.get.on"));
					} else {
						context.getSource().sendFeedback(Component.translatable("command.flywheel.limit_updates.get.off"));
					}
					return Command.SINGLE_SUCCESS;
				})
				.then(ClientCommands.literal("on")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.limitUpdates = true;
							FabricFlwConfig.INSTANCE.save();
							context.getSource().sendFeedback(Component.translatable("command.flywheel.limit_updates.set.on"));
							Minecraft.getInstance().levelExtractor.allChanged();
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.limitUpdates = false;
							FabricFlwConfig.INSTANCE.save();
							context.getSource().sendFeedback(Component.translatable("command.flywheel.limit_updates.set.off"));
							Minecraft.getInstance().levelExtractor.allChanged();
							return Command.SINGLE_SUCCESS;
						})));

		command.then(ClientCommands.literal("lightSmoothness")
				.then(ClientCommands.argument("mode", LightSmoothnessArgument.INSTANCE)
						.executes(context -> {
							var oldValue = FabricFlwConfig.INSTANCE.backendConfig.lightSmoothness;
							var newValue = context.getArgument("mode", LightSmoothness.class);

							if (oldValue != newValue) {
								FabricFlwConfig.INSTANCE.backendConfig.lightSmoothness = newValue;
								FabricFlwConfig.INSTANCE.save();
								PipelineCompiler.deleteAll();
							}
							return Command.SINGLE_SUCCESS;
						})));

		command.then(createDebugCommand());

		dispatcher.register(command);
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> createDebugCommand() {
		var debug = ClientCommands.literal("debug");

		debug.then(ClientCommands.literal("crumbling")
				.then(ClientCommands.argument("pos", BlockPosArgument.blockPos())
						.then(ClientCommands.argument("stage", IntegerArgumentType.integer(0, 9))
								.executes(context -> {
									Entity executor = context.getSource()
											.getEntity();

									if (executor == null) {
										return 0;
									}

									BlockPos pos = getBlockPos(context, "pos");
									int value = IntegerArgumentType.getInteger(context, "stage");

									executor.level()
											.destroyBlockProgress(executor.getId(), pos, value);

									return Command.SINGLE_SUCCESS;
								}))));

		debug.then(ClientCommands.literal("shader")
				.then(ClientCommands.argument("mode", DebugModeArgument.INSTANCE)
						.executes(context -> {
							DebugMode mode = context.getArgument("mode", DebugMode.class);
							FrameUniforms.debugMode(mode);
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("frustum")
				.then(ClientCommands.literal("capture")
						.executes(context -> {
							FrameUniforms.captureFrustum();
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("unpause")
						.executes(context -> {
							FrameUniforms.unpauseFrustum();
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("lightSections")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							BackendDebugFlags.LIGHT_STORAGE_VIEW = true;
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							BackendDebugFlags.LIGHT_STORAGE_VIEW = false;
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("pauseUpdates")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							ImplDebugFlags.PAUSE_UPDATES = true;
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							ImplDebugFlags.PAUSE_UPDATES = false;
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("info")
				.executes(context -> {
					context.getSource()
							.sendFeedback(FlwDebugInfo.getDebugCommandInfo());
					return Command.SINGLE_SUCCESS;
				}));

		return debug;
	}

	// Client version of BlockPosArgument.getBlockPos
	private static BlockPos getBlockPos(CommandContext<FabricClientCommandSource> context, String name) {
		Coordinates coordinates = context.getArgument(name, Coordinates.class);
		FabricClientCommandSource source = context.getSource();
		Vec3 sourcePos = source.getPosition();

		return switch (coordinates) {
		case WorldCoordinates world -> BlockPos.containing(
			world.x().get(sourcePos.x), world.y().get(sourcePos.y), world.z().get(sourcePos.z));
		case LocalCoordinates local -> localBlockPos(sourcePos, source.getRotation(), local);
		default -> throw new IllegalArgumentException("Unsupported client block-position coordinates: " + coordinates);
		};
	}

	private static BlockPos localBlockPos(Vec3 sourcePos, Vec2 rotation, LocalCoordinates local) {
		float yawX = Mth.cos((rotation.y + 90) * Mth.DEG_TO_RAD);
		float yawZ = Mth.sin((rotation.y + 90) * Mth.DEG_TO_RAD);
		float forwardsHPitch = Mth.cos(-rotation.x * Mth.DEG_TO_RAD);
		float forwardsVPitch = Mth.sin(-rotation.x * Mth.DEG_TO_RAD);
		float upHPitch = Mth.cos((-rotation.x + 90) * Mth.DEG_TO_RAD);
		float upVPitch = Mth.sin((-rotation.x + 90) * Mth.DEG_TO_RAD);

		Vec3 forwards = new Vec3(yawX * forwardsHPitch, forwardsVPitch, yawZ * forwardsHPitch);
		Vec3 up = new Vec3(yawX * upHPitch, upVPitch, yawZ * upHPitch);
		Vec3 left = forwards.cross(up).scale(-1.0);
		double x = forwards.x * local.forwards() + up.x * local.up() + left.x * local.left();
		double y = forwards.y * local.forwards() + up.y * local.up() + left.y * local.left();
		double z = forwards.z * local.forwards() + up.z * local.up() + left.z * local.left();
		return BlockPos.containing(sourcePos.x + x, sourcePos.y + y, sourcePos.z + z);
	}
}

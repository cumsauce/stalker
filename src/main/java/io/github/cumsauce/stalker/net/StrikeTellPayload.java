package io.github.cumsauce.stalker.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C payload for the strike tell: the villager's head position and how long the
 * client should spend snapping the camera toward it. Fire-and-forget; a vanilla
 * client simply never sees the effect (sound-only tell).
 */
public record StrikeTellPayload(double villagerX, double villagerY, double villagerZ, int durationTicks)
		implements CustomPacketPayload {

	/** Channel id under the mod namespace. */
	public static final Identifier ID = Identifier.fromNamespaceAndPath("stalker", "strike_tell");
	/** Payload type token used for registration and receivers. */
	public static final CustomPacketPayload.Type<StrikeTellPayload> TYPE = new CustomPacketPayload.Type<>(ID);

	/** Wire codec: four doubles plus one varint, matching the record component order. */
	public static final StreamCodec<ByteBuf, StrikeTellPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, StrikeTellPayload::villagerX,
			ByteBufCodecs.DOUBLE, StrikeTellPayload::villagerY,
			ByteBufCodecs.DOUBLE, StrikeTellPayload::villagerZ,
			ByteBufCodecs.VAR_INT, StrikeTellPayload::durationTicks,
			StrikeTellPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

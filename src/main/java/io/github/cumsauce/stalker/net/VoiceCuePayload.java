package io.github.cumsauce.stalker.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C payload for one Foley voice cue: where it plays, which {@code VoiceCue} it is by name, and
 * its pitch/volume (the laugh's later cadence beats are derived from this pitch client-side).
 * Fire-and-forget; a client without Foley receives it and silently declines to play — the existing
 * vanilla cues are unaffected either way.
 */
public record VoiceCuePayload(double x, double y, double z, String cue, float pitch, float volume)
		implements CustomPacketPayload {

	/** Channel id under the mod namespace. */
	public static final Identifier ID = Identifier.fromNamespaceAndPath("stalker", "voice_cue");
	/** Payload type token used for registration and receivers. */
	public static final CustomPacketPayload.Type<VoiceCuePayload> TYPE = new CustomPacketPayload.Type<>(ID);

	/** Wire codec: three doubles, the cue name, and two floats, matching the record component order. */
	public static final StreamCodec<ByteBuf, VoiceCuePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, VoiceCuePayload::x,
			ByteBufCodecs.DOUBLE, VoiceCuePayload::y,
			ByteBufCodecs.DOUBLE, VoiceCuePayload::z,
			ByteBufCodecs.STRING_UTF8, VoiceCuePayload::cue,
			ByteBufCodecs.FLOAT, VoiceCuePayload::pitch,
			ByteBufCodecs.FLOAT, VoiceCuePayload::volume,
			VoiceCuePayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

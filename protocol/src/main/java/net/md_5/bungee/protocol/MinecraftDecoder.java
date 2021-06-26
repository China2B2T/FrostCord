package net.md_5.bungee.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.AllArgsConstructor;
import lombok.Setter;

import java.util.List;

@AllArgsConstructor
public class MinecraftDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Setter
    private Protocol protocol;
    private final boolean server;
    @Setter
    private int protocolVersion;
    @Setter
    private boolean supportsForge = false;

    public MinecraftDecoder(Protocol protocol, boolean server, int protocolVersion) {
        this.protocol = protocol;
        this.server = server;
        this.protocolVersion = protocolVersion;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // See Varint21FrameDecoder for the general reasoning. We add this here as ByteToMessageDecoder#handlerRemoved()
        // will fire any cumulated data through the pipeline, so we want to try and stop it here.
        if (!ctx.channel().isActive()) {
            return;
        }

        Protocol.DirectionData prot = (server) ? protocol.TO_SERVER : protocol.TO_CLIENT;

        // FlameCord - Check size before decoding
        if (prot == protocol.TO_SERVER) {
            final int readableBytes = in.readableBytes();
            final int capacity = in.capacity();

            if (readableBytes > 2097152) {
                throw new IllegalStateException("Error decoding packet with too many readableBytes: " + readableBytes);
            } else if (capacity > 2097152) {
                throw new IllegalStateException("Error decoding packet with too big capacity: " + capacity);
            }
        }

        ByteBuf slice = in.copy(); // Can't slice this one due to EntityMap :(

        try {
            // Waterfall start
            if (in.readableBytes() == 0 && !server) {
                return;
            }
            // Waterfall end

            int packetId = DefinedPacket.readVarInt(in);

            DefinedPacket packet = prot.createPacket(packetId, protocolVersion, supportsForge);
            if (packet != null) {
                doLengthSanityChecks(in, packet, prot.getDirection(), packetId); // Waterfall: Additional DoS mitigations
                packet.read0(in, prot.getDirection(), protocolVersion);

                if (in.isReadable()) {
                    throw new BadPacketException("Did not read all bytes from packet " + packet.getClass() + " " + packetId + " Protocol " + protocol + " Direction " + prot.getDirection());
                }
            } else {
                in.skipBytes(in.readableBytes());
            }

            out.add(new PacketWrapper(packet, slice));
            slice = null;
        } finally {
            if (slice != null) {
                slice.release();
            }
        }
    }

    // Cached Exceptions:
    private static final CorruptedFrameException PACKET_LENGTH_OVERSIZED =
            new CorruptedFrameException("A packet could not be decoded because it was too large. For more "
                    + "information, launch Waterfall with -Dwaterfall.packet-decode-logging=true");
    private static final CorruptedFrameException PACKET_LENGTH_UNDERSIZED =
            new CorruptedFrameException("A packet could not be decoded because it was smaller than allowed. For more "
                    + "information, launch Waterfall with -Dwaterfall.packet-decode-logging=true");


    private void doLengthSanityChecks(ByteBuf buf, DefinedPacket packet,
                                      ProtocolConstants.Direction direction, int packetId) throws Exception {
        int expectedMinLen = packet.expectedMinLength(buf, direction, protocolVersion);
        int expectedMaxLen = packet.expectedMaxLength(buf, direction, protocolVersion);
        if (expectedMaxLen != -1 && buf.readableBytes() > expectedMaxLen) {
            throw handleOverflow(packet, expectedMaxLen, buf.readableBytes(), packetId);
        }
        if (buf.readableBytes() < expectedMinLen) {
            throw handleUnderflow(packet, expectedMaxLen, buf.readableBytes(), packetId);
        }
    }

    private Exception handleOverflow(DefinedPacket packet, int expected, int actual, int packetId) {
        return PACKET_LENGTH_OVERSIZED;
    }

    private Exception handleUnderflow(DefinedPacket packet, int expected, int actual, int packetId) {
        return PACKET_LENGTH_UNDERSIZED;
    }
}

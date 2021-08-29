package net.md_5.bungee.protocol.packet;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.md_5.bungee.protocol.AbstractPacketHandler;
import net.md_5.bungee.protocol.PortablePacket;
import net.md_5.bungee.protocol.ProtocolConstants;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EncryptionRequest extends PortablePacket {

    private String serverId;
    private byte[] publicKey;
    private byte[] verifyToken;


    @Override
    public void v17Read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        serverId = readString(buf);
        publicKey = v17readArray(buf);
        verifyToken = v17readArray(buf);
    }

    @Override
    public void read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        serverId = readString(buf);
        publicKey = readArray(buf);
        verifyToken = readArray(buf);
    }


    @Override
    public void v17Write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        writeString(serverId, buf);
        v17writeArray(publicKey, buf, false);
        v17writeArray(verifyToken, buf, false);
    }

    @Override
    public void write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        writeString(serverId, buf);
        writeArray(publicKey, buf);
        writeArray(verifyToken, buf);
    }

    @Override
    public void handle(AbstractPacketHandler handler) throws Exception {
        handler.handle(this);
    }
}

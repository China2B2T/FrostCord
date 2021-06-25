package net.md_5.bungee.protocol.packet;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.md_5.bungee.protocol.AbstractPacketHandler;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.ProtocolConstants;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class LoginRequest extends DefinedPacket
{

    private String data;

    @Override
    public void read(ByteBuf buf)
    {
        data = readString( buf, 16 );
    }

    @Override
    public void write(ByteBuf buf)
    {
        writeString( data, buf );
    }

    @Override
    public void handle(AbstractPacketHandler handler) throws Exception
    {
        handler.handle( this );
    }

    // Waterfall start: Additional DoS mitigations, courtesy of Velocity
    public int expectedMaxLength(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        // Accommodate the rare (but likely malicious) use of UTF-8 usernames, since it is technically
        // legal on the protocol level.
        return 1 + (16 * 4);
    }
    // Waterfall end
}

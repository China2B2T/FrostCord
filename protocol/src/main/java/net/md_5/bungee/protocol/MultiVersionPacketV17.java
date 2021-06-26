package net.md_5.bungee.protocol;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;

import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.ProtocolConstants;

public abstract class MultiVersionPacketV17 extends DefinedPacket
{

    protected void v17Read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion)
    {
        v17Read( buf );
    }

    @Override
    public void read0(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion)
    {
        switch ( protocolVersion )
        {
            case ProtocolConstants.MINECRAFT_1_7_2:
            case ProtocolConstants.MINECRAFT_1_7_6:
                v17Read(buf, direction, protocolVersion);
                break;
            default:
                read(buf, direction, protocolVersion);
                break;
        }
    }

    protected void v17Write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion)
    {
        v17Write( buf );
    }

    @Override
    public void write0(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion)
    {
        switch ( protocolVersion )
        {
            case ProtocolConstants.MINECRAFT_1_7_2:
            case ProtocolConstants.MINECRAFT_1_7_6:
                v17Write(buf, direction, protocolVersion);
                break;
            default:
                write(buf, direction, protocolVersion);
                break;
        }
    }
    protected void v17Read(ByteBuf buf)
    {
        throw new UnsupportedOperationException( "Packet must implement read method" );
    }

    protected void v17Write(ByteBuf buf)
    {
        throw new UnsupportedOperationException( "Packet must implement write method" );
    }

    public static void v17writeArray(byte[] b, ByteBuf buf, boolean allowExtended)
    {
        // (Integer.MAX_VALUE & 0x1FFF9A ) = 2097050 - Forge's current upper limit
        if ( allowExtended )
        {
            Preconditions.checkArgument( b.length <= ( Integer.MAX_VALUE & 0x1FFF9A ), "Cannot send array longer than 2097050 (got %s bytes)", b.length );
        } else
        {
            Preconditions.checkArgument( b.length <= Short.MAX_VALUE, "Cannot send array longer than Short.MAX_VALUE (got %s bytes)", b.length );
        }
        // Write a 2 or 3 byte number that represents the length of the packet. (3 byte "shorts" for Forge only)
        // No vanilla packet should give a 3 byte packet, this method will still retain vanilla behaviour.
        writeVarShort( buf, b.length );
        buf.writeBytes( b );
    }

    public static byte[] v17readArray(ByteBuf buf)
    {
        // Read in a 2 or 3 byte number that represents the length of the packet. (3 byte "shorts" for Forge only)
        // No vanilla packet should give a 3 byte packet, this method will still retain vanilla behaviour.
        int len = readVarShort( buf );

        // (Integer.MAX_VALUE & 0x1FFF9A ) = 2097050 - Forge's current upper limit
        Preconditions.checkArgument( len <= ( Integer.MAX_VALUE & 0x1FFF9A ), "Cannot receive array longer than 2097050 (got %s bytes)", len );

        byte[] ret = new byte[ len ];
        buf.readBytes( ret );
        return ret;
    }
}

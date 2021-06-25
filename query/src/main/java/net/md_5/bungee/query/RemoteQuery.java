package net.md_5.bungee.query;

import io.netty.channel.ChannelOption;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import java.net.InetSocketAddress;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;

@RequiredArgsConstructor
public class RemoteQuery
{

    private final ProxyServer bungee;
    private final ListenerInfo listener;

    public void start(Class<? extends Channel> channel, InetSocketAddress address, EventLoopGroup eventLoop, ChannelFutureListener future)
    {
        int bufferSize = 8192; // FlameCord

        new Bootstrap()
                .channel( channel )
                .group( eventLoop )
                .handler( new QueryHandler( bungee, listener ) )
                .localAddress( address )
                .option(ChannelOption.SO_RCVBUF, bufferSize) // FlameCord
                .option(ChannelOption.SO_SNDBUF, bufferSize) // FlameCord
                .bind().addListener( future );
    }
}

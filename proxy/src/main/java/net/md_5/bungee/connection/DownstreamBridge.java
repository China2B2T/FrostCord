package net.md_5.bungee.connection;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.unix.DomainSocketAddress;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.ServerConnection.KeepAliveData;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.score.Team;
import net.md_5.bungee.api.score.*;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.entitymap.EntityMap;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.PacketHandler;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.*;
import net.md_5.bungee.tab.TabList;

import java.io.DataInput;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RequiredArgsConstructor
public class DownstreamBridge extends PacketHandler {

    private final ProxyServer bungee;
    private final UserConnection con;
    private final ServerConnection server;

    @Override
    public void exception(Throwable t) throws Exception {
        if (server.isObsolete()) {
            // do not perform any actions if the user has already moved
            return;
        }

        // Waterfall start
        ServerInfo def = con.updateAndGetNextServer(server.getInfo());
        ServerKickEvent event = bungee.getPluginManager().callEvent(new ServerKickEvent(con, server.getInfo(), TextComponent.fromLegacyText(bungee.getTranslation("server_went_down")), def, ServerKickEvent.State.CONNECTED, ServerKickEvent.Cause.EXCEPTION));
        if (event.isCancelled() && event.getCancelServer() != null) {
            server.setObsolete(true);
            con.connectNow(event.getCancelServer(), ServerConnectEvent.Reason.SERVER_DOWN_REDIRECT);
        } else {
            con.disconnect0(event.getKickReasonComponent());
        }
        // Waterfall end
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception {
        // We lost connection to the server
        server.getInfo().removePlayer(con);
        if (bungee.getReconnectHandler() != null) {
            bungee.getReconnectHandler().setServer(con);
        }

        if (!server.isObsolete()) {
            // Waterfall start
            ServerInfo def = con.updateAndGetNextServer(server.getInfo());
            ServerKickEvent event = bungee.getPluginManager().callEvent(new ServerKickEvent(con, server.getInfo(), TextComponent.fromLegacyText(bungee.getTranslation("lost_connection")), def, ServerKickEvent.State.CONNECTED, ServerKickEvent.Cause.LOST_CONNECTION));
            if (event.isCancelled() && event.getCancelServer() != null) {
                server.setObsolete(true);
                con.connectNow(event.getCancelServer());
            } else {
                con.disconnect0(event.getKickReasonComponent());
            }
            // Waterfall end
        }

        ServerDisconnectEvent serverDisconnectEvent = new ServerDisconnectEvent(con, server.getInfo());
        bungee.getPluginManager().callEvent(serverDisconnectEvent);
    }

    @Override
    public boolean shouldHandle(PacketWrapper packet) throws Exception {
        return !server.isObsolete();
    }

    @Override
    public void handle(PacketWrapper packet) throws Exception {
        EntityMap rewrite = con.getEntityRewrite();
        if (rewrite != null) {
            rewrite.rewriteClientbound(packet.buf, con.getServerEntityId(), con.getClientEntityId(), con.getPendingConnection().getVersion());
        }
        con.sendPacket(packet);
    }

    @Override
    public void handle(KeepAlive alive) throws Exception {
        int timeout = bungee.getConfig().getTimeout();
        if (timeout <= 0 || server.getKeepAlives().size() < timeout / 50) // Some people disable timeout, otherwise allow a theoretical maximum of 1 keepalive per tick
        {
            server.getKeepAlives().add(new KeepAliveData(alive.getRandomId(), System.currentTimeMillis()));
        }
    }

    @Override
    public void handle(PlayerListItem playerList) throws Exception {
        con.getTabListHandler().onUpdate(TabList.rewrite(playerList));
        throw CancelSendSignal.INSTANCE; // Always throw because of profile rewriting
    }

    @Override
    public void handle(ScoreboardObjective objective) throws Exception {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        switch (objective.getAction()) {
            case 0:
                serverScoreboard.addObjective(new Objective(objective.getName(), objective.getValue(), objective.getType() != null ? objective.getType().toString() : null)); // Travertine - 1.7 protocol support
                break;
            case 1:
                serverScoreboard.removeObjective(objective.getName());
                break;
            case 2:
                Objective oldObjective = serverScoreboard.getObjective(objective.getName());
                if (oldObjective != null) {
                    oldObjective.setValue(objective.getValue());
                    oldObjective.setType(objective.getType() != null ? objective.getType().toString() : null); // Travertine - 1.7 protocol support
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown objective action: " + objective.getAction());
        }
    }

    @Override
    public void handle(ScoreboardScore score) throws Exception {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        switch (score.getAction()) {
            case 0:
                Score s = new Score(score.getItemName(), score.getScoreName(), score.getValue());
                serverScoreboard.removeScore(score.getItemName());
                serverScoreboard.addScore(s);
                break;
            case 1:
                serverScoreboard.removeScore(score.getItemName());
                break;
            default:
                throw new IllegalArgumentException("Unknown scoreboard action: " + score.getAction());
        }
    }

    @Override
    public void handle(ScoreboardDisplay displayScoreboard) throws Exception {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        serverScoreboard.setName(displayScoreboard.getName());
        serverScoreboard.setPosition(Position.values()[displayScoreboard.getPosition()]);
    }

    @Override
    public void handle(net.md_5.bungee.protocol.packet.Team team) throws Exception {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        // Remove team and move on
        if (team.getMode() == 1) {
            serverScoreboard.removeTeam(team.getName());
            return;
        }

        // Create or get old team
        Team t;
        if (team.getMode() == 0) {
            t = new Team(team.getName());
            serverScoreboard.addTeam(t);
        } else {
            t = serverScoreboard.getTeam(team.getName());
        }

        if (t != null) {
            if (team.getMode() == 0 || team.getMode() == 2) {
                t.setDisplayName(team.getDisplayName());
                t.setPrefix(team.getPrefix());
                t.setSuffix(team.getSuffix());
                t.setFriendlyFire(team.getFriendlyFire());
                t.setNameTagVisibility(team.getNameTagVisibility());
                t.setCollisionRule(team.getCollisionRule());
                t.setColor(team.getColor());
            }
            if (team.getPlayers() != null) {
                for (String s : team.getPlayers()) {
                    if (team.getMode() == 0 || team.getMode() == 3) {
                        t.addPlayer(s);
                    } else if (team.getMode() == 4) {
                        t.removePlayer(s);
                    }
                }
            }
        }
    }

    @Override
    public void handle(PluginMessage pluginMessage) throws Exception {
        DataInput in = pluginMessage.getStream();
        PluginMessageEvent event = new PluginMessageEvent(server, con, pluginMessage.getTag(), pluginMessage.getData().clone());

        if (bungee.getPluginManager().callEvent(event).isCancelled()) {
            throw CancelSendSignal.INSTANCE;
        }

        if (pluginMessage.getTag().equals(con.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_13 ? "minecraft:brand" : "MC|Brand")) {
            ByteBuf brand = Unpooled.wrappedBuffer(pluginMessage.getData());

            if (ProtocolConstants.isAfterOrEq(con.getPendingConnection().getVersion(), ProtocolConstants.MINECRAFT_1_8)) {
                try {
                    String serverBrand = DefinedPacket.readString(brand);
                    brand.release();
                    brand = ByteBufAllocator.DEFAULT.heapBuffer();
                    DefinedPacket.writeString(bungee.getName() + " <- " + serverBrand, brand); // Waterfall
                    pluginMessage.setData(DefinedPacket.toArray(brand));
                    brand.release();
                } catch (Exception ProtocolHacksSuck) {
                    return;
                }
            } else {
                String serverBrand = new String(pluginMessage.getData(), StandardCharsets.UTF_8);
                pluginMessage.setData((bungee.getName() + " <- " + serverBrand).getBytes(StandardCharsets.UTF_8)); // Travertine
            }
            // changes in the packet are ignored so we need to send it manually
            con.unsafe().sendPacket(pluginMessage);
            throw CancelSendSignal.INSTANCE;
        }

        if (pluginMessage.getTag().equals("BungeeCord")) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            String subChannel = in.readUTF();

            if (subChannel.equals("ForwardToPlayer")) {
                ProxiedPlayer target = bungee.getPlayer(in.readUTF());
                if (target != null) {
                    // Read data from server
                    String channel = in.readUTF();
                    short len = in.readShort();
                    byte[] data = new byte[len];
                    in.readFully(data);

                    // Prepare new data to send
                    out.writeUTF(channel);
                    out.writeShort(data.length);
                    out.write(data);
                    byte[] payload = out.toByteArray();

                    target.getServer().sendData("BungeeCord", payload);
                }

                // Null out stream, important as we don't want to send to ourselves
                out = null;
            }
            if (subChannel.equals("Forward")) {
                // Read data from server
                String target = in.readUTF();
                String channel = in.readUTF();
                short len = in.readShort();
                byte[] data = new byte[len];
                in.readFully(data);

                // Prepare new data to send
                out.writeUTF(channel);
                out.writeShort(data.length);
                out.write(data);
                byte[] payload = out.toByteArray();

                // Null out stream, important as we don't want to send to ourselves
                out = null;

                if (target.equals("ALL")) {
                    for (ServerInfo server : bungee.getServers().values()) {
                        if (server != this.server.getInfo()) {
                            server.sendData("BungeeCord", payload);
                        }
                    }
                } else if (target.equals("ONLINE")) {
                    for (ServerInfo server : bungee.getServers().values()) {
                        if (server != this.server.getInfo()) {
                            server.sendData("BungeeCord", payload, false);
                        }
                    }
                } else {
                    ServerInfo server = bungee.getServerInfo(target);
                    if (server != null) {
                        server.sendData("BungeeCord", payload);
                    }
                }
            }
            if (subChannel.equals("Connect")) {
                ServerInfo server = bungee.getServerInfo(in.readUTF());
                if (server != null) {
                    con.connect(server, ServerConnectEvent.Reason.PLUGIN_MESSAGE);
                }
            }
            if (subChannel.equals("ConnectOther")) {
                ProxiedPlayer player = bungee.getPlayer(in.readUTF());
                if (player != null) {
                    ServerInfo server = bungee.getServerInfo(in.readUTF());
                    if (server != null) {
                        player.connect(server);
                    }
                }
            }
            if (subChannel.equals("IP")) {
                out.writeUTF("IP");
                if (con.getSocketAddress() instanceof InetSocketAddress) {
                    out.writeUTF(con.getAddress().getHostString());
                    out.writeInt(con.getAddress().getPort());
                } else {
                    out.writeUTF("unix://" + ((DomainSocketAddress) con.getSocketAddress()).path());
                    out.writeInt(0);
                }
            }
            if (subChannel.equals("IPOther")) {
                ProxiedPlayer player = bungee.getPlayer(in.readUTF());
                if (player != null) {
                    out.writeUTF("IPOther");
                    out.writeUTF(player.getName());
                    if (player.getSocketAddress() instanceof InetSocketAddress) {
                        InetSocketAddress address = (InetSocketAddress) player.getSocketAddress();
                        out.writeUTF(address.getHostString());
                        out.writeInt(address.getPort());
                    } else {
                        out.writeUTF("unix://" + ((DomainSocketAddress) player.getSocketAddress()).path());
                        out.writeInt(0);
                    }
                }
            }
            if (subChannel.equals("PlayerCount")) {
                String target = in.readUTF();
                out.writeUTF("PlayerCount");
                if (target.equals("ALL")) {
                    out.writeUTF("ALL");
                    out.writeInt(bungee.getOnlineCount());
                } else {
                    ServerInfo server = bungee.getServerInfo(target);
                    if (server != null) {
                        out.writeUTF(server.getName());
                        out.writeInt(server.getPlayers().size());
                    }
                }
            }
            if (subChannel.equals("PlayerList")) {
                String target = in.readUTF();
                out.writeUTF("PlayerList");
                if (target.equals("ALL")) {
                    out.writeUTF("ALL");
                    out.writeUTF(Util.csv(bungee.getPlayers()));
                } else {
                    ServerInfo server = bungee.getServerInfo(target);
                    if (server != null) {
                        out.writeUTF(server.getName());
                        out.writeUTF(Util.csv(server.getPlayers()));
                    }
                }
            }
            if (subChannel.equals("GetServers")) {
                out.writeUTF("GetServers");
                out.writeUTF(Util.csv(bungee.getServers().keySet()));
            }
            if (subChannel.equals("Message")) {
                String target = in.readUTF();
                String message = in.readUTF();
                if (target.equals("ALL")) {
                    for (ProxiedPlayer player : bungee.getPlayers()) {
                        player.sendMessage(message);
                    }
                } else {
                    ProxiedPlayer player = bungee.getPlayer(target);
                    if (player != null) {
                        player.sendMessage(message);
                    }
                }
            }
            if (subChannel.equals("MessageRaw")) {
                String target = in.readUTF();
                BaseComponent[] message = ComponentSerializer.parse(in.readUTF());
                if (target.equals("ALL")) {
                    for (ProxiedPlayer player : bungee.getPlayers()) {
                        player.sendMessage(message);
                    }
                } else {
                    ProxiedPlayer player = bungee.getPlayer(target);
                    if (player != null) {
                        player.sendMessage(message);
                    }
                }
            }
            if (subChannel.equals("GetServer")) {
                out.writeUTF("GetServer");
                out.writeUTF(server.getInfo().getName());
            }
            if (subChannel.equals("UUID")) {
                out.writeUTF("UUID");
                out.writeUTF(con.getUUID());
            }
            if (subChannel.equals("UUIDOther")) {
                ProxiedPlayer player = bungee.getPlayer(in.readUTF());
                if (player != null) {
                    out.writeUTF("UUIDOther");
                    out.writeUTF(player.getName());
                    out.writeUTF(player.getUUID());
                }
            }
            if (subChannel.equals("ServerIP")) {
                ServerInfo info = bungee.getServerInfo(in.readUTF());
                if (info != null && !info.getAddress().isUnresolved()) {
                    out.writeUTF("ServerIP");
                    out.writeUTF(info.getName());
                    out.writeUTF(info.getAddress().getAddress().getHostAddress());
                    out.writeShort(info.getAddress().getPort());
                }
            }
            if (subChannel.equals("KickPlayer")) {
                ProxiedPlayer player = bungee.getPlayer(in.readUTF());
                if (player != null) {
                    String kickReason = in.readUTF();
                    player.disconnect(new TextComponent(kickReason));
                }
            }

            // Check we haven't set out to null, and we have written data, if so reply back back along the BungeeCord channel
            if (out != null) {
                byte[] b = out.toByteArray();
                if (b.length != 0) {
                    server.sendData("BungeeCord", b);
                }
            }

            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public void handle(Kick kick) throws Exception {
        ServerInfo def = con.updateAndGetNextServer(server.getInfo());
        if (Objects.equals(server.getInfo(), def)) {
            def = null;
        }
        ServerKickEvent event = bungee.getPluginManager().callEvent(new ServerKickEvent(con, server.getInfo(), ComponentSerializer.parse(kick.getMessage()), def, ServerKickEvent.State.CONNECTED, ServerKickEvent.Cause.SERVER)); // Waterfall
        if (event.isCancelled() && event.getCancelServer() != null) {
            con.connectNow(event.getCancelServer(), ServerConnectEvent.Reason.KICK_REDIRECT);
        } else {
            con.disconnect0(event.getKickReasonComponent()); // TODO: Prefix our own stuff.
        }
        server.setObsolete(true);
        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(SetCompression setCompression) throws Exception {
        server.getCh().setCompressionThreshold(setCompression.getThreshold());
    }

    @Override
    public void handle(TabCompleteResponse tabCompleteResponse) throws Exception {
        List<String> commands = tabCompleteResponse.getCommands();
        if (commands == null) {
            commands = Lists.transform(tabCompleteResponse.getSuggestions().getList(), new Function<Suggestion, String>() {
                @Override
                public String apply(Suggestion input) {
                    return input.getText();
                }
            });
        }

        TabCompleteResponseEvent tabCompleteResponseEvent = new TabCompleteResponseEvent(server, con, new ArrayList<>(commands));
        if (!bungee.getPluginManager().callEvent(tabCompleteResponseEvent).isCancelled()) {
            // Take action only if modified
            if (!commands.equals(tabCompleteResponseEvent.getSuggestions())) {
                if (tabCompleteResponse.getCommands() != null) {
                    // Classic style
                    tabCompleteResponse.setCommands(tabCompleteResponseEvent.getSuggestions());
                } else {
                    // Brigadier style
                    final StringRange range = tabCompleteResponse.getSuggestions().getRange();
                    tabCompleteResponse.setSuggestions(new Suggestions(range, Lists.transform(tabCompleteResponseEvent.getSuggestions(), new Function<String, Suggestion>() {
                        @Override
                        public Suggestion apply(String input) {
                            return new Suggestion(range, input);
                        }
                    })));
                }
            }

            con.unsafe().sendPacket(tabCompleteResponse);
        }

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(BossBar bossBar) {
        switch (bossBar.getAction()) {
            // Handle add bossbar
            case 0:
                con.getSentBossBars().add(bossBar.getUuid());
                break;
            // Handle remove bossbar
            case 1:
                con.getSentBossBars().remove(bossBar.getUuid());
                break;
        }
    }

    // Waterfall start
    @Override
    public void handle(EntityEffect entityEffect) throws Exception {
        // Don't send any potions when switching between servers (which involves a handshake), which can trigger a race
        // condition on the client.
        if (this.con.getForgeClientHandler().isForgeUser() && !this.con.getForgeClientHandler().isHandshakeComplete()) {
            throw CancelSendSignal.INSTANCE;
        }
        con.getPotions().put(rewriteEntityId(entityEffect.getEntityId()), entityEffect.getEffectId());
    }

    @Override
    public void handle(EntityRemoveEffect removeEffect) throws Exception {
        con.getPotions().remove(rewriteEntityId(removeEffect.getEntityId()), removeEffect.getEffectId());
    }

    private int rewriteEntityId(int entityId) {
        if (entityId == con.getServerEntityId()) {
            return con.getClientEntityId();
        }
        return entityId;
    }
    // Waterfall end

    @Override
    public void handle(Respawn respawn) {
        con.setDimension(respawn.getDimension());
    }

    @Override
    public void handle(Commands commands) throws Exception {
        boolean modified = false;

        for (Map.Entry<String, Command> command : bungee.getPluginManager().getCommands()) {
            if (!bungee.getDisabledCommands().contains(command.getKey()) && commands.getRoot().getChild(command.getKey()) == null && command.getValue().hasPermission(con)) {
                LiteralCommandNode dummy = LiteralArgumentBuilder.literal(command.getKey())
                        .then(RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString())
                                .suggests(Commands.SuggestionRegistry.ASK_SERVER))
                        .build();
                commands.getRoot().addChild(dummy);

                modified = true;
            }
        }

        if (modified) {
            con.unsafe().sendPacket(commands);
            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public String toString() {
        return "[" + con.getAddress() + "|" + con.getName() + "] <-> DownstreamBridge <-> [" + server.getInfo().getName() + "]";
    }
}

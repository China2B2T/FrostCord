package net.md_5.bungee.connection;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;
import net.md_5.bungee.*;
import net.md_5.bungee.api.*;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.http.HttpClient;
import net.md_5.bungee.jni.cipher.BungeeCipher;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.HandlerBoss;
import net.md_5.bungee.netty.PacketHandler;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.netty.cipher.CipherDecoder;
import net.md_5.bungee.netty.cipher.CipherEncoder;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.*;
import net.md_5.bungee.util.BoundedArrayList;
import net.md_5.bungee.util.BufUtil;
import net.md_5.bungee.util.QuietException;

import javax.crypto.SecretKey;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static net.md_5.bungee.FrostLandUtils.fetchUUID;

@RequiredArgsConstructor
public class InitialHandler extends PacketHandler implements PendingConnection {

    private final BungeeCord bungee;
    private ChannelWrapper ch;
    @Getter
    private final ListenerInfo listener;
    @Getter
    private Handshake handshake;
    @Getter
    private LoginRequest loginRequest;
    private EncryptionRequest request;
    @Getter
    private final List<PluginMessage> relayMessages = new BoundedArrayList<>(128);
    private State thisState = State.HANDSHAKE;
    private final Unsafe unsafe = new Unsafe() {
        @Override
        public void sendPacket(DefinedPacket packet) {
            ch.write(packet);
        }
    };
    @Getter
    private boolean onlineMode = BungeeCord.getInstance().config.isOnlineMode();
    @Getter
    private InetSocketAddress virtualHost;
    private String name;
    @Getter
    private UUID uniqueId;
    @Getter
    private UUID offlineId;
    @Getter
    private UUID onlineId;
    @Getter
    private LoginResult loginProfile;
    @Getter
    private boolean legacy;
    @Getter
    private String extraDataInHandshake = "";

    @Override
    public boolean shouldHandle(PacketWrapper packet) throws Exception {
        return !ch.isClosing();
    }

    private enum State {

        HANDSHAKE, STATUS, PING, USERNAME, ENCRYPT, FINISHED;
    }

    private boolean canSendKickMessage() {
        return thisState == State.USERNAME || thisState == State.ENCRYPT || thisState == State.FINISHED;
    }

    private static boolean containsHanScript(String s) {
        return s.codePoints().anyMatch(
                codepoint ->
                        Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);
    }

    @Override
    public void connected(ChannelWrapper channel) throws Exception {
        this.ch = channel;
    }

    @Override
    public void exception(Throwable t) throws Exception {
        if (canSendKickMessage()) {
            disconnect(ChatColor.RED + Util.exception(t));
        } else {
            ch.close();
        }
    }

    @Override
    public void handle(PacketWrapper packet) throws Exception {
        if (packet.packet == null) {
            throw new QuietException("Unexpected packet received during login process! " + BufUtil.dump(packet.buf, 16));
        }
    }

    @Override
    public void handle(PluginMessage pluginMessage) throws Exception {
        // TODO: Unregister?
        if (PluginMessage.SHOULD_RELAY.apply(pluginMessage)) {
            relayMessages.add(pluginMessage);
        }
    }

    @Override
    public void handle(LegacyHandshake legacyHandshake) throws Exception {
        this.legacy = true;
        ch.close(bungee.getTranslation("outdated_client", bungee.getGameVersion()));
    }

    @Override
    public void handle(LegacyPing ping) throws Exception {
        this.legacy = true;
        final boolean v1_5 = ping.isV1_5();

        ServerPing legacy = new ServerPing(new ServerPing.Protocol(bungee.getName() + " " + bungee.getGameVersion(), bungee.getProtocolVersion()),
                new ServerPing.Players(listener.getMaxPlayers(), bungee.getOnlineCount(), null),
                new TextComponent(TextComponent.fromLegacyText(listener.getMotd())), (Favicon) null);

        Callback<ProxyPingEvent> callback = (result, error) -> {
            if (ch.isClosed()) {
                return;
            }

            ServerPing legacy1 = result.getResponse();

            // FlameCord - Close and return if legacy == null
            if (legacy1 == null) {
                ch.close();
                return;
            }

            String kickMessage;

            if (v1_5) {
                kickMessage = ChatColor.DARK_BLUE
                        + "\00" + 127
                        + '\00' + legacy1.getVersion().getName()
                        + '\00' + getFirstLine(legacy1.getDescription())
                        + '\00' + legacy1.getPlayers().getOnline()
                        + '\00' + legacy1.getPlayers().getMax();
            } else {
                // Clients <= 1.3 don't support colored motds because the color char is used as delimiter
                kickMessage = ChatColor.stripColor(getFirstLine(legacy1.getDescription()))
                        + '\u00a7' + legacy1.getPlayers().getOnline()
                        + '\u00a7' + legacy1.getPlayers().getMax();
            }

            ch.close(kickMessage);
        };

        bungee.getPluginManager().callEvent(new ProxyPingEvent(this, legacy, callback));
    }

    private static String getFirstLine(String str) {
        int pos = str.indexOf('\n');
        return pos == -1 ? str : str.substring(0, pos);
    }

    private ServerPing getPingInfo(String motd, int protocol) {
        return new ServerPing(
                new ServerPing.Protocol(bungee.getName() + " " + bungee.getGameVersion(), protocol),
                new ServerPing.Players(listener.getMaxPlayers(), bungee.getOnlineCount(), null),
                motd, BungeeCord.getInstance().config.getFaviconObject()
        );
    }

    @Override
    public void handle(StatusRequest statusRequest) throws Exception {
        // FrostCord - Remove unnecessary warning
        Preconditions.checkState(thisState == State.STATUS, "Not expecting STATUS");

        // FrostCord - Close if invalid
//        if (thisState == State.STATUS) {
//            String address = ch.getRemoteAddress ().toString ();
//            Firewall.tickViolation ( address, 1 );
//
//            if (Firewall.isBlocked ( address )) {
//                ch.close ();
//            }
//        }

        ServerInfo forced = AbstractReconnectHandler.getForcedHost(this);
        final String motd = (forced != null) ? forced.getMotd() : listener.getMotd();
        final int protocol = (ProtocolConstants.SUPPORTED_VERSION_IDS.contains(handshake.getProtocolVersion())) ? handshake.getProtocolVersion() : bungee.getProtocolVersion();

        Callback<ServerPing> pingBack = (result, error) -> {
            if (error != null) {
                result = getPingInfo(bungee.getTranslation("ping_cannot_connect"), protocol);
                bungee.getLogger().log(Level.WARNING, "Error pinging remote server", error);
            }

            Callback<ProxyPingEvent> callback = (pingResult, error1) -> {
                if (pingResult.getResponse() == null) {
                    ch.close();
                    return;
                } else if (ch.isClosed()) {
                    return;
                }

                Gson gson = handshake.getProtocolVersion() == ProtocolConstants.MINECRAFT_1_7_2 ? BungeeCord.getInstance().gsonLegacy : BungeeCord.getInstance().gson; // Travertine
                if (bungee.getConnectionThrottle() != null) {
                    bungee.getConnectionThrottle().unthrottle(getSocketAddress());
                }

                if (ProtocolConstants.isBeforeOrEq(handshake.getProtocolVersion(), ProtocolConstants.MINECRAFT_1_8)) {
                    // Minecraft < 1.9 doesn't send string server descriptions as chat components. Older 1.7
                    // clients even crash when encountering a chat component instead of a string. To be on the
                    // safe side, always send legacy descriptions for < 1.9 clients.
                    JsonElement element = gson.toJsonTree(pingResult.getResponse());
                    Preconditions.checkArgument(element.isJsonObject(), "Response is not a JSON object");
                    JsonObject object = element.getAsJsonObject();
                    object.addProperty("description", pingResult.getResponse().getDescription());

                    unsafe.sendPacket(new StatusResponse(gson.toJson(element)));
                } else {
                    unsafe.sendPacket(new StatusResponse(gson.toJson(pingResult.getResponse())));
                }
                // Travertine end
            };

            bungee.getPluginManager().callEvent(new ProxyPingEvent(InitialHandler.this, result, callback));
        };

        if (forced != null && listener.isPingPassthrough()) {
            ((BungeeServerInfo) forced).ping(pingBack, handshake.getProtocolVersion());
        } else {
            pingBack.done(getPingInfo(motd, protocol), null);
        }

        thisState = State.PING;
    }

    @Override
    public void handle(PingPacket ping) throws Exception {
        unsafe.sendPacket(ping);

        // FlameCord - Close instead of disconnect
        ch.close();
    }

    @Override
    public void handle(Handshake handshake) throws Exception {
        Preconditions.checkState(thisState == State.HANDSHAKE, "Not expecting HANDSHAKE");
        this.handshake = handshake;
        ch.setVersion(handshake.getProtocolVersion());

        // Starting with FML 1.8, a "\0FML\0" token is appended to the handshake. This interferes
        // with Bungee's IP forwarding, so we detect it, and remove it from the host string, for now.
        // We know FML appends \00FML\00. However, we need to also consider that other systems might
        // add their own data to the end of the string. So, we just take everything from the \0 character
        // and save it for later.
        if (handshake.getHost().contains("\0")) {
            String[] split = handshake.getHost().split("\0", 2);
            handshake.setHost(split[0]);
            extraDataInHandshake = "\0" + split[1];
        }

        // SRV records can end with a . depending on DNS / client.
        if (handshake.getHost().endsWith(".")) {
            handshake.setHost(handshake.getHost().substring(0, handshake.getHost().length() - 1));
        }

        this.virtualHost = InetSocketAddress.createUnresolved(handshake.getHost(), handshake.getPort());

        bungee.getPluginManager().callEvent(new PlayerHandshakeEvent(InitialHandler.this, handshake));

        switch (handshake.getRequestedProtocol()) {
            case 1:
                // Ping
                if (bungee.getConfig().isLogPings()) {
                    bungee.getLogger().log(Level.INFO, "{0} has pinged", this);
                }
                thisState = State.STATUS;
                ch.setProtocol(Protocol.STATUS);
                break;
            case 2:
                // Login
                bungee.getLogger().log(Level.INFO, "{0} has connected", this);
                thisState = State.USERNAME;
                ch.setProtocol(Protocol.LOGIN);

                if (!ProtocolConstants.SUPPORTED_VERSION_IDS.contains(handshake.getProtocolVersion())) {
                    if (handshake.getProtocolVersion() > bungee.getProtocolVersion()) {
                        disconnect(bungee.getTranslation("outdated_server", bungee.getGameVersion()));
                    } else {
                        disconnect(bungee.getTranslation("outdated_client", bungee.getGameVersion()));
                    }
                    return;
                }
                break;
            default:
                throw new QuietException("Cannot request protocol " + handshake.getRequestedProtocol());
        }
    }

    @Override
    public void handle(LoginRequest loginRequest) throws Exception {
        Preconditions.checkState(thisState == State.USERNAME, "Not expecting USERNAME");
        this.loginRequest = loginRequest;

        if (getName().contains(" ")) {
            disconnect(bungee.getTranslation("name_invalid"));
            return;
        }

        // FrostCord - It won't be limited after 1.17, remove?
        if (getName().length() > 16) {
            disconnect(bungee.getTranslation("name_too_long"));
            return;
        }

        int limit = BungeeCord.getInstance().config.getPlayerLimit();
        if (limit > 0 && bungee.getOnlineCount() >= limit) {
            disconnect(bungee.getTranslation("proxy_full"));
            return;
        }

        // If offline mode and they are already on, don't allow connect
        // We can just check by UUID here as names are based on UUID
        if (bungee.getPlayer(getUniqueId()) != null) {
            disconnect(bungee.getTranslation("already_connected_proxy"));
            return;
        }

        Callback<PreLoginEvent> callback = (result, error) -> {
            if (result.isCancelled()) {
                disconnect(result.getCancelReasonComponents());
                return;
            }
            if (ch.isClosed()) {
                return;
            }
            if (onlineMode && !containsHanScript(result.getConnection().getName())) {
                unsafe().sendPacket(request = EncryptionUtil.encryptRequest());
            } else {
                result.getConnection().setOnlineMode(false);
                finish();
            }
            thisState = State.ENCRYPT;
        };

        // fire pre login event
        bungee.getPluginManager().callEvent(new PreLoginEvent(InitialHandler.this, callback));
    }

    @Override
    public void handle(final EncryptionResponse encryptResponse) throws Exception {
        Preconditions.checkState(thisState == State.ENCRYPT, "Not expecting ENCRYPT");

        // FlameCord - Finish here to avoid multiple incoming packets
        thisState = State.FINISHED;
        SecretKey sharedKey = EncryptionUtil.getSecret(encryptResponse, request);
        BungeeCipher decrypt = EncryptionUtil.getCipher(false, sharedKey);
        ch.addBefore(PipelineUtils.FRAME_DECODER, PipelineUtils.DECRYPT_HANDLER, new CipherDecoder(decrypt));
        BungeeCipher encrypt = EncryptionUtil.getCipher(true, sharedKey);
        ch.addBefore(PipelineUtils.FRAME_PREPENDER, PipelineUtils.ENCRYPT_HANDLER, new CipherEncoder(encrypt));

        String encName = URLEncoder.encode(InitialHandler.this.getName(), "UTF-8");

        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        for (byte[] bit : new byte[][]
                {
                        request.getServerId().getBytes("ISO_8859_1"), sharedKey.getEncoded(), EncryptionUtil.keys.getPublic().getEncoded()
                }) {
            sha.update(bit);
        }
        String encodedHash = URLEncoder.encode(new BigInteger(sha.digest()).toString(16), "UTF-8");

        String[] sourceList = new String[]{"sessionserver.mojang.com", "sessionserver.steampowered.workers.dev"};

        String preventProxy = (BungeeCord.getInstance().config.isPreventProxyConnections() && getSocketAddress() instanceof InetSocketAddress) ? "&ip=" + URLEncoder.encode(getAddress().getAddress().getHostAddress(), "UTF-8") : "";

        AtomicBoolean state = new AtomicBoolean(true);

        for (String i : sourceList) {
            String authURL = "https://" + i + "/session/minecraft/hasJoined?username=" + encName + "&serverId=" + encodedHash + preventProxy;

            Callback<String> handler = (result, error) -> {
                if (error == null) {
                    val obj = BungeeCord.getInstance().gson.fromJson(result, LoginResult.class);
                    if (obj != null && obj.getId() != null) {
                        loginProfile = obj;
                        name = obj.getName();
                        onlineId = Util.getUUID(obj.getId());
                        finish();
                        return;
                    }
                    disconnect(bungee.getTranslation("offline_mode_player"));
                } else {
                    state.set(false);
                    // disconnect(bungee.getTranslation("mojang_fail"));
                    bungee.getLogger().log(Level.WARNING, "Error authenticating " + getName() + " with " + i + ": " + error.getMessage());
                    error.printStackTrace();
                }
            };

            HttpClient.get(authURL, ch.getHandle().eventLoop(), handler);

            if (!state.get()) {
                HttpClient.get(authURL, ch.getHandle().eventLoop(), handler);
            } else {
                return;
            }
        }

        disconnect(bungee.getTranslation("mojang_fail"));
    }

    private void finish() {
        // FlameCord - Finish here to avoid multiple incoming packets
        thisState = State.FINISHED;

        // Check for multiple connections
        // We have to check for the old name first
        ProxiedPlayer oldName = bungee.getPlayer(getName());
        if (oldName != null) {
            // TODO See #1218
            oldName.disconnect(new TextComponent(bungee.getTranslation("already_connected_proxy")));
        }
        // And then also for their old UUID
        ProxiedPlayer oldID = bungee.getPlayer(getUniqueId());
        if (oldID != null) {
            // TODO See #1218
            oldID.disconnect(new TextComponent(bungee.getTranslation("already_connected_proxy")));
        }

        // TODO FrostCord - Should synchronize UUID with upstream service here.
        offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + getName()).getBytes(Charsets.UTF_8));

        String internalId = null;

        if (onlineId != null) {
            try {
                internalId = fetchUUID(onlineId.toString());
            } catch (UnsupportedEncodingException ignored) {
            }

            if (internalId == null) {
                disconnect(new TextComponent(ChatColor.RED + "Cannot fetch your profile! Please contact the administrators."));
                return;
            }
        } else {
            try {
                internalId = fetchUUID(getName());
            } catch (UnsupportedEncodingException ignored) {
            }

            if (internalId == null) {
                disconnect(new TextComponent(ChatColor.RED + "Cannot fetch your profile! Please contact the administrators."));
                return;
            }
        }
        uniqueId = Util.getUUID(internalId);

        Callback<LoginEvent> complete = (result, error) -> {
            if (result.isCancelled()) {
                disconnect(result.getCancelReasonComponents());
                return;
            }
            if (ch.isClosed()) {
                return;
            }

            ch.getHandle().eventLoop().execute(() -> {
                if (!ch.isClosing()) {
                    UserConnection userCon = new UserConnection(bungee, ch, getName(), InitialHandler.this);
                    userCon.setCompressionThreshold(BungeeCord.getInstance().config.getCompressionThreshold());
                    userCon.init();

                    unsafe.sendPacket(new LoginSuccess(getUniqueId(), getName()));
                    ch.setProtocol(Protocol.GAME);

                    ch.getHandle().pipeline().get(HandlerBoss.class).setHandler(new UpstreamBridge(bungee, userCon));
                    bungee.getPluginManager().callEvent(new PostLoginEvent(userCon));
                    ServerInfo server;
                    if (bungee.getReconnectHandler() != null) {
                        server = bungee.getReconnectHandler().getServer(userCon);
                    } else {
                        server = AbstractReconnectHandler.getForcedHost(InitialHandler.this);
                    }
                    if (server == null) {
                        server = bungee.getServerInfo(listener.getDefaultServer());
                    }

                    userCon.connect(server, null, true, ServerConnectEvent.Reason.JOIN_PROXY);
                }
            });
        };

        // fire login event
        bungee.getPluginManager().callEvent(new LoginEvent(InitialHandler.this, complete));
    }

    @Override
    public void handle(LoginPayloadResponse response) throws Exception {
        disconnect("Unexpected custom LoginPayloadResponse");
    }

    @Override
    public void disconnect(String reason) {
        if (canSendKickMessage()) {
            disconnect(TextComponent.fromLegacyText(reason));
        } else {
            ch.close();
        }
    }

    @Override
    public void disconnect(final BaseComponent... reason) {
        if (canSendKickMessage()) {
            // FlameCord - Changed delayedClose to close
            ch.close(new Kick(ComponentSerializer.toString(reason)));
        } else {
            ch.close();
        }
    }

    @Override
    public void disconnect(BaseComponent reason) {
        disconnect(new BaseComponent[]
                {
                        reason
                });
    }

    @Override
    public String getName() {
        return (name != null) ? name : (loginRequest == null) ? null : loginRequest.getData();
    }

    @Override
    public int getVersion() {
        return (handshake == null) ? -1 : handshake.getProtocolVersion();
    }

    @Override
    public InetSocketAddress getAddress() {
        return (InetSocketAddress) getSocketAddress();
    }

    @Override
    public SocketAddress getSocketAddress() {
        return ch.getRemoteAddress();
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    @Override
    public void setOnlineMode(boolean onlineMode) {
        Preconditions.checkState(thisState == State.USERNAME, "Can only set online mode status whilst state is username");
        this.onlineMode = onlineMode;
    }

    @Override
    public void setUniqueId(UUID uuid) {
        Preconditions.checkState(thisState == State.USERNAME, "Can only set uuid while state is username");
        // Preconditions.checkState ( !onlineMode, "Can only set uuid when online mode is false" );
        this.uniqueId = uuid;
    }

    @Override
    public String getUUID() {
        return uniqueId.toString().replace("-", "");
    }

    @Override
    public String toString() {
        return "[" + getSocketAddress() + (getName() != null ? "|" + getName() : "") + "] <-> InitialHandler";
    }

    @Override
    public boolean isConnected() {
        return !ch.isClosed();
    }
}

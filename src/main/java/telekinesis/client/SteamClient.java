package telekinesis.client;

import com.google.protobuf.ByteString;
import io.netty.channel.EventLoopGroup;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import telekinesis.client.module.GameConnectTokens;
import telekinesis.client.module.SteamFriends;
import telekinesis.connection.ClientMessageContext;
import telekinesis.connection.ConnectionState;
import telekinesis.connection.SteamConnection;
import telekinesis.message.SimpleClientMessageTypeRegistry;
import telekinesis.message.proto.generated.steam.SM_ClientServer;
import telekinesis.model.AppId;
import telekinesis.model.ClientMessageHandler;
import telekinesis.model.SteamClientDelegate;
import telekinesis.model.steam.EMsg;
import telekinesis.model.steam.EOSType;
import telekinesis.model.steam.EPersonaState;
import telekinesis.model.steam.EResult;
import telekinesis.util.MessageDispatcher;
import telekinesis.util.Publisher;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
public class SteamClient extends Publisher<SteamClient> implements ClientMessageHandler {

    private static final SimpleClientMessageTypeRegistry HANDLED_MESSAGES = new SimpleClientMessageTypeRegistry()
            .registerProto(EMsg.ClientLogon.v(), SM_ClientServer.CMsgClientLogon.class)
            .registerProto(EMsg.ClientLogOnResponse.v(), SM_ClientServer.CMsgClientLogonResponse.class)
            .registerProto(EMsg.ClientUpdateMachineAuth.v(), SM_ClientServer.CMsgClientUpdateMachineAuth.class)
            .registerProto(EMsg.ClientUpdateMachineAuthResponse.v(), SM_ClientServer.CMsgClientUpdateMachineAuthResponse.class)
            .registerProto(EMsg.ClientAccountInfo.v(), SM_ClientServer.CMsgClientAccountInfo.class)
            .registerProto(EMsg.ClientNewLoginKey.v(), SM_ClientServer.CMsgClientNewLoginKey.class)
            .registerProto(EMsg.ClientNewLoginKeyAccepted.v(), SM_ClientServer.CMsgClientNewLoginKeyAccepted.class)
            .registerProto(EMsg.ClientHeartBeat.v(), SM_ClientServer.CMsgClientHeartBeat.class)
            .registerProto(EMsg.ClientPlayingSessionState.v(), SM_ClientServer.CMsgClientPlayingSessionState.class)
            .registerProto(EMsg.ClientGamesPlayedWithDataBlob.v(), SM_ClientServer.CMsgClientGamesPlayed.class);

    private static final String DEFAULT_STEAM_SERVICE_HOST = "162.254.195.44";
    private static final int DEFAULT_STEAM_SERVICE_PORT = 27020;

    @Getter private final EventLoopGroup workerGroup;
    @Getter private final SteamClientDelegate delegate;
    @Getter private final SteamDatagramNetwork datagramNetwork;
    @Getter private final MessageDispatcher selfHandledMessageDispatcher;
    @Getter private final Set<SteamClientModule> modules;

    @Getter private SteamConnection connection;
    @Getter private int publicIp;
    @Getter private SteamClientState clientState;

    @Getter private String steamServiceHost = DEFAULT_STEAM_SERVICE_HOST;
    @Getter private int steamServicePort = DEFAULT_STEAM_SERVICE_PORT;

    public SteamClient(EventLoopGroup workerGroup, SteamClientDelegate delegate) {
        this.workerGroup = workerGroup;
        this.delegate = delegate;
        this.datagramNetwork = new SteamDatagramNetwork(workerGroup.next(), delegate);
        this.modules = new LinkedHashSet<>();

        selfHandledMessageDispatcher = new MessageDispatcher();
        selfHandledMessageDispatcher.subscribe(SM_ClientServer.CMsgClientLogonResponse.class, this::handleClientLogonResponse);
        selfHandledMessageDispatcher.subscribe(SM_ClientServer.CMsgClientUpdateMachineAuth.class, this::handleClientUpdateMachineAuth);
        selfHandledMessageDispatcher.subscribe(SM_ClientServer.CMsgClientAccountInfo.class, this::handleClientAccountInfo);
        selfHandledMessageDispatcher.subscribe(SM_ClientServer.CMsgClientNewLoginKey.class, this::handleClientNewLoginKey);
        selfHandledMessageDispatcher.subscribe(SM_ClientServer.CMsgClientPlayingSessionState.class, this::handleClientPlayingSessionState);

        clientState = SteamClientState.LOGGED_OFF;

        connection = new SteamConnection(workerGroup, this);
        connection.addRegistry(HANDLED_MESSAGES);
        connection.subscribe(ConnectionState.class, this::handleConnectionStateChange);

        registerModule(new SteamFriends());
        registerModule(new GameConnectTokens());
    }

    public void registerModule(SteamClientModule module) {
        if (modules.add(module)) {
            connection.addRegistry(module.getHandledMessages());
            module.setSteamClient(this);
        }
    }

    public void unregisterModule(SteamClientModule module) {
        if (modules.remove(module)) {
            connection.removeRegistry(module.getHandledMessages());
            module.setSteamClient(null);
        }
    }

    public <T extends SteamClientModule> T getModule(Class<T> moduleClass) {
        for (SteamClientModule module : modules) {
            if (moduleClass.isAssignableFrom(module.getClass())) {
                return (T) module;
            }
        }
        return null;
    }

    public void connect() {
        connection.connect(steamServiceHost, steamServicePort);
        datagramNetwork.connect();
    }

    public void disconnect() {
        connection.disconnect();
        datagramNetwork.disconnect();
    }

    public void send(Object body) {
        send(AppId.STEAM, body);
    }

    public void send(int appId, Object body) {
        connection.send(appId, body);
    }

    public <P> void request(Object body, Handler<ClientMessageContext, P> callback) {
        request(AppId.STEAM, body, callback);
    }

    public <P> void request(int appId, Object body, Handler<ClientMessageContext, P> callback) {
        connection.request(appId, body, callback);
    }

    // TODO: only for testing, remove this
    public boolean isConnectionAlive() {
        return connection != null && connection.isAlive();
    }

    protected void performLogon() throws IOException {
        log.info("performing logon for {}", delegate.getAccountName());

        changeClientState(SteamClientState.LOGGING_IN);

        SM_ClientServer.CMsgClientLogon.Builder logon = SM_ClientServer.CMsgClientLogon.newBuilder();
        logon.setProtocolVersion(65575);
        logon.setAccountName(delegate.getAccountName());
        logon.setPassword(delegate.getPassword());
        byte[] sentrySha = delegate.getSentrySha1();
        if (sentrySha != null) {
            logon.setEresultSentryfile(EResult.OK.v());
            logon.setShaSentryfile(ByteString.copyFrom(sentrySha));
        } else {
            logon.setEresultSentryfile(EResult.FileNotFound.v());
        }
        connection.send(logon);
    }

    public void startPlaying(int appId, String appName) {
        SM_ClientServer.CMsgClientGamesPlayed.GamePlayed.Builder gp = SM_ClientServer.CMsgClientGamesPlayed.GamePlayed.newBuilder();
        gp.setGameId((long) appId);
        gp.setGameExtraInfo(appName);
        SM_ClientServer.CMsgClientGamesPlayed.Builder msg = SM_ClientServer.CMsgClientGamesPlayed.newBuilder();
        msg.addGamesPlayed(gp);
        msg.setClientOsType(EOSType.Unknown.v());
        connection.send(msg);
    }

    public void stopPlaying() {
        SM_ClientServer.CMsgClientGamesPlayed.Builder msg = SM_ClientServer.CMsgClientGamesPlayed.newBuilder();
        msg.setClientOsType(EOSType.Unknown.v());
        connection.send(msg);
    }

    private void changeClientState(SteamClientState newState) {
        if (clientState == newState) {
            log.debug("clientState is already {}", newState);
            return;
        }
        log.debug("updating clientState to {}", newState);
        clientState = newState;
        publish(this, clientState);
    }

    protected void handleConnectionStateChange(SteamConnection conn, ConnectionState newState) throws IOException {
        switch(newState) {
            case CONNECTING:
                changeClientState(SteamClientState.CONNECTING);
                break;
            case ESTABLISHED:
                performLogon();
                break;
            case BROKEN:
                connection.disconnect();
                break;
            case CONNECTION_FAILED:
            case CLOSED:
                changeClientState(SteamClientState.LOGGED_OFF);
                break;
            case LOST:
                changeClientState(SteamClientState.LOST);
                //connection = null;
                break;

            default:
                break;
        }
    }

    @Override
    public void handleClientMessage(ClientMessageContext ctx, Object message) throws Exception {
        selfHandledMessageDispatcher.handleClientMessage(ctx, message);
        for (SteamClientModule module : modules) {
            module.handleClientMessage(ctx, message);
        }
    }

    protected void handleClientLogonResponse(ClientMessageContext ctx, SM_ClientServer.CMsgClientLogonResponse msg) {
        log.info("received logon response");
        final EResult eResult = EResult.f(msg.getEresult());
        switch(eResult) {
            case OK:
                connection.enableHeartbeat(msg.getOutOfGameHeartbeatSeconds());
                getModule(SteamFriends.class).setPersonaState(EPersonaState.Online);
                changeClientState(SteamClientState.LOGGED_ON);
                publicIp = msg.getPublicIp();
                break;
            default:
                log.error("steam logon failed: {}", eResult);
                changeClientState(SteamClientState.LOGON_FAILED);
        }
    }

    protected void handleClientUpdateMachineAuth(ClientMessageContext ctx, SM_ClientServer.CMsgClientUpdateMachineAuth msg) throws IOException, NoSuchAlgorithmException {
        log.info("received update machine auth request");
        if (msg.getCubtowrite() != msg.getBytes().size()) {
            throw new IOException("assert failed: bytes.size != cubtowrite");
        }

        SM_ClientServer.CMsgClientUpdateMachineAuthResponse.Builder builder = SM_ClientServer.CMsgClientUpdateMachineAuthResponse.newBuilder();
        try {
            delegate.writeFile(msg.getFilename(), msg.getOffset(), msg.getBytes().asReadOnlyByteBuffer());
            builder.setShaFile(ByteString.copyFrom(delegate.getSentrySha1()));
            builder.setEresult(EResult.OK.v());
            builder.setCubwrote(msg.getCubtowrite());
            builder.setFilename(msg.getFilename());
        } catch (IOException e) {
            builder.setEresult(EResult.DiskFull.v());
        }
        ctx.reply(builder);

    }

    protected void handleClientAccountInfo(ClientMessageContext ctx, SM_ClientServer.CMsgClientAccountInfo msg) {
        log.info("received account info");
    }

    protected void handleClientNewLoginKey(ClientMessageContext ctx, SM_ClientServer.CMsgClientNewLoginKey msg) throws IOException {
        log.info("received client new login key");
        SM_ClientServer.CMsgClientNewLoginKeyAccepted.Builder response = SM_ClientServer.CMsgClientNewLoginKeyAccepted.newBuilder();
        response.setUniqueId(msg.getUniqueId());
        ctx.reply(response);
    }

    private void handleClientPlayingSessionState(ClientMessageContext ctx, SM_ClientServer.CMsgClientPlayingSessionState msg) {
        publish(this, msg);
    }
}

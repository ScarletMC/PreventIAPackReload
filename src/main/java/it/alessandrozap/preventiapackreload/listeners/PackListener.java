package it.alessandrozap.preventiapackreload.listeners;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientResourcePackStatus;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerResourcePackRemove;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerResourcePackSend;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientResourcePackStatus;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientResourcePackStatus.Result;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackSend;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PackListener implements PacketListener {
    private static final UUID LEGACY_ID = new UUID(0L, 0L);

    private static final int R_SUCCESSFULLY_LOADED = 0;
    private static final int R_ACCEPTED = 3;
    private static final int R_DOWNLOADED = 4;

    private final Map<UUID, PackState> states = new ConcurrentHashMap<>();

    @Override
    public void onPacketSend(PacketSendEvent event) {
        boolean play = event.getPacketType() == PacketType.Play.Server.RESOURCE_PACK_SEND;
        boolean config = event.getPacketType() == PacketType.Configuration.Server.RESOURCE_PACK_SEND;
        if (!play && !config) return;

        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;

        String hash;
        UUID packId;
        if (play) {
            WrapperPlayServerResourcePackSend w = new WrapperPlayServerResourcePackSend(event);
            hash = w.getHash();
            packId = w.getPackId();
        } else {
            WrapperConfigServerResourcePackSend w = new WrapperConfigServerResourcePackSend(event);
            hash = w.getHash();
            packId = w.getPackId();
        }

        if (hash == null || hash.isEmpty()) return;
        hash = hash.toLowerCase(Locale.ROOT);

        ClientVersion version = user.getClientVersion();
        boolean modern = version.isNewerThanOrEquals(ClientVersion.V_1_20_3);
        PackState state = states.computeIfAbsent(user.getUUID(), k -> new PackState());

        if (state.applied.containsKey(hash)) {
            if (fakeLoaded(user, packId, config, modern)) {
                event.setCancelled(true);
                return;
            }
        }

        if (modern) {
            Iterator<Map.Entry<String, UUID>> it = state.applied.entrySet().iterator();
            while (it.hasNext()) {
                UUID oldId = it.next().getValue();
                if (!oldId.equals(packId)) {
                    if (config) user.sendPacket(new WrapperConfigServerResourcePackRemove(oldId));
                    else user.sendPacket(new WrapperPlayServerResourcePackRemove(oldId));
                }
                it.remove();
            }
            state.pending.put(packId, hash);
        } else state.legacyPending = hash;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        boolean play = event.getPacketType() == PacketType.Play.Client.RESOURCE_PACK_STATUS;
        boolean config = event.getPacketType() == PacketType.Configuration.Client.RESOURCE_PACK_STATUS;
        if (!play && !config) return;

        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;

        PackState state = states.get(user.getUUID());
        if (state == null) return;

        UUID packId;
        Result result;
        if (play) {
            WrapperPlayClientResourcePackStatus w = new WrapperPlayClientResourcePackStatus(event);
            packId = w.getPackId();
            result = w.getResult();
        } else {
            WrapperConfigClientResourcePackStatus w = new WrapperConfigClientResourcePackStatus(event);
            packId = w.getPackId();
            result = toPlay(w.getResult());
        }

        boolean modern = user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_20_3);
        switch (result) {
            case SUCCESSFULLY_LOADED -> {
                if (modern) {
                    String hash = state.pending.remove(packId);
                    if (hash != null) state.applied.put(hash, packId);
                } else if (state.legacyPending != null) {
                    state.applied.clear();
                    state.applied.put(state.legacyPending, LEGACY_ID);
                    state.legacyPending = null;
                }
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                if (modern) state.pending.remove(packId);
                else state.legacyPending = null;
            }
            default -> {}
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid != null) forget(uuid);
    }

    private boolean fakeLoaded(User user, UUID packId, boolean config, boolean modern) {
        ClientVersion version = user.getClientVersion();
        if (modern && packId == null) return false;

        Channel backend = backendChannel(user.getUUID());
        if (backend == null || !backend.isActive()) return false;

        int packetId = config ? PacketType.Configuration.Client.RESOURCE_PACK_STATUS.getId(version) : PacketType.Play.Client.RESOURCE_PACK_STATUS.getId(version);
        if (packetId < 0) return false;

        int[] steps = modern ? new int[]{R_ACCEPTED, R_DOWNLOADED, R_SUCCESSFULLY_LOADED} : new int[]{R_ACCEPTED, R_SUCCESSFULLY_LOADED};
        for (int result : steps) {
            ByteBuf buf = backend.alloc().buffer();
            writeVarInt(buf, packetId);
            if (modern) {
                buf.writeLong(packId.getMostSignificantBits());
                buf.writeLong(packId.getLeastSignificantBits());
            }
            writeVarInt(buf, result);
            backend.write(buf);
        }
        backend.flush();
        return true;
    }

    private static Channel backendChannel(UUID uuid) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        if (player == null) return null;
        Server server = player.getServer();
        if (server == null) return null;
        try {
            Object wrapper = server.getClass().getMethod("getCh").invoke(server);
            return (Channel) wrapper.getClass().getMethod("getHandle").invoke(wrapper);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public void forget(UUID player) {
        states.remove(player);
    }

    private static Result toPlay(WrapperConfigClientResourcePackStatus.Result r) {
        return Result.valueOf(r.name());
    }

    private static final class PackState {
        final Map<String, UUID> applied = new ConcurrentHashMap<>();
        final Map<UUID, String> pending = new ConcurrentHashMap<>();
        volatile String legacyPending;
    }
}
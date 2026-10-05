package it.alessandrozap.preventiapackreload;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import it.alessandrozap.preventiapackreload.listeners.PackListener;
import net.md_5.bungee.api.plugin.Plugin;

public final class PreventIAPackReload extends Plugin {
    @Override
    public void onEnable() {
        PackListener packListener = new PackListener();
        PacketEvents.getAPI().getEventManager().registerListener(packListener, PacketListenerPriority.NORMAL);
        getLogger().info("PreventIAPackReload enabled");
    }
}

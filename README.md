# PreventIAPackReload

A **BungeeCord** plugin that stops the **ItemsAdder** resource pack from being reloaded when a player switches servers inside the network.

## The problem

In a BungeeCord network with ItemsAdder on several backends, each server sends the resource pack to the player as soon as they join. Even when it's the same pack the player already has, the client downloads it and applies it again every time. That means a loading screen, a wait and lag on every server switch.

## How it works

The plugin works at the packet level, through PacketEvents, directly on the proxy:

1. **Tracks applied packs.** When a backend sends a resource pack, the plugin stores its SHA-1 hash. When the client confirms it has loaded the pack (`SUCCESSFULLY_LOADED`), the plugin marks it as applied for that player.
2. **Blocks duplicate packs.** If a server sends a pack with the same hash as one already applied, the plugin cancels the packet. The client sees nothing and reloads nothing.
3. **Answers the backend for the client.** The plugin sends the backend the same statuses a real client would (`ACCEPTED` → `DOWNLOADED` → `SUCCESSFULLY_LOADED`). ItemsAdder then treats the pack as loaded and doesn't kick the player.
4. **Handles new packs.** A pack with a new hash goes through normally. On 1.20.3+ clients, the plugin also removes the old packs still applied, so they don't pile up.
5. **Cleans up.** When a player disconnects from the proxy, the plugin deletes the data it kept about them.

The plugin works in both the **Play** and **Configuration** (1.20.2+) phases. It supports:
- **modern clients (1.20.3+)**, which can have several packs identified by UUID;
- **legacy clients**, which have a single pack.

> The plugin only handles packs sent with a hash. A pack sent without a hash goes straight through and the client loads it normally.

## Requirements

| Requirement | Version |
|---|---|
| Proxy | BungeeCord (or compatible forks, e.g. Waterfall) |
| Java | 21+ |
| [PacketEvents](https://github.com/retrooper/packetevents) | 2.14.0+ — **required dependency** |
| [ItemsAdder](https://itemsadder.devs.beer/) | **4.0 or newer** (on the backend servers) |

## Installation

1. Download the BungeeCord build of **PacketEvents** and put it in the proxy's `plugins/` folder.
2. Put `PreventIAPackReload.jar` in the proxy's `plugins/` folder.
3. Restart the proxy.
4. Make sure every backend runs ItemsAdder 4.0+ and sends **the same resource pack** (same hash). The plugin only skips the reload when the hash matches.

No configuration needed.

## Building

    ./gradlew build

The jar is created in `build/libs/`.

## Author

[AlessandroZap](https://github.com/Kawi16)
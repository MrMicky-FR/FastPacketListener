package fr.mrmicky.fastpacketlistener;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Small Bukkit packet listener with 1.8 to 1.13.2 support !
 * <p>
 * You can find the project on <a href="https://github.com/MrMicky-FR/FastPacketListener">GitHub</a>
 */
public final class FastPacketListener {

    private static final AtomicInteger COUNT = new AtomicInteger();

    private static final Method PLAYER_GET_HANDLE;
    private static final Field PLAYER_CONNECTION;
    private static final Field NETWORK_MANAGER;
    private static final Field CHANNEL;

    static {
        try {
            Class<?> craftPlayerClass = FastReflection.obcClass("entity.CraftPlayer");
            Class<?> entityPlayerClass = FastReflection.nmsClass("EntityPlayer");
            Class<?> playerConnectionClass = FastReflection.nmsClass("PlayerConnection");
            Class<?> networkManagerClass = FastReflection.nmsClass("NetworkManager");

            PLAYER_GET_HANDLE = craftPlayerClass.getDeclaredMethod("getHandle");
            PLAYER_CONNECTION = entityPlayerClass.getDeclaredField("playerConnection");
            NETWORK_MANAGER = playerConnectionClass.getDeclaredField("networkManager");
            CHANNEL = networkManagerClass.getDeclaredField("channel");
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private List<PacketHandler> packetHandlers = new ArrayList<>();
    private Map<UUID, Channel> channels = new ConcurrentHashMap<>();

    private String handlerName;
    private Plugin plugin;

    public FastPacketListener(Plugin plugin) {
        this.plugin = plugin;

        handlerName = "FastPacketListener-" + plugin.getName() + '-' + COUNT.getAndIncrement();

        Bukkit.getPluginManager().registerEvents(new Listener() {

            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                addPlayer(event.getPlayer());
            }

            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent event) {
                channels.remove(event.getPlayer().getUniqueId());
            }

            @EventHandler
            public void onPluginDisable(PluginDisableEvent event) {
                if (event.getPlugin() == plugin) {
                    channels.values().forEach(FastPacketListener.this::removeChannel);
                    channels.clear();
                }
            }
        }, plugin);

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::addPlayer));
    }

    public void addPlayer(Player p) {
        if (channels.containsKey(p.getUniqueId())) {
            return;
        }

        Channel channel = resolveChannel(p);

        channel.pipeline().addBefore("packet_handler", handlerName, new ChannelDuplexHandler() {

            @Override
            public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
                if (handlePacket(p, context.channel(), packet, PacketDirection.IN)) {
                    super.channelRead(context, packet);
                }
            }

            @Override
            public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
                if (handlePacket(p, context.channel(), packet, PacketDirection.OUT)) {
                    super.write(context, packet, promise);
                }
            }
        });

        channels.put(p.getUniqueId(), channel);
    }

    public void removePlayer(Player p) {
        Channel channel = channels.remove(p.getUniqueId());

        if (channel != null) {
            removeChannel(channel);
        }
    }

    public void addHandler(PacketHandler handler) {
        packetHandlers.add(handler);
    }

    public void removeHandler(PacketHandler handler) {
        packetHandlers.remove(handler);
    }

    public Optional<Channel> getChannel(Player p) {
        return Optional.ofNullable(channels.get(p.getUniqueId()));
    }

    public Channel getOrResolveChannel(Player p) {
        return getChannel(p).orElseGet(() -> resolveChannel(p));
    }

    private Channel resolveChannel(Player p) {
        try {
            Object entityPlayer = PLAYER_GET_HANDLE.invoke(p);
            Object playerConnection = PLAYER_CONNECTION.get(entityPlayer);
            Object networkManager = NETWORK_MANAGER.get(playerConnection);

            return (Channel) CHANNEL.get(networkManager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot get channel for " + p.getName(), e);
        }
    }

    private void removeChannel(Channel channel) {
        channel.eventLoop().execute(() -> channel.pipeline().remove(handlerName));
    }

    private boolean handlePacket(Player p, Channel channel, Object packet, PacketDirection direction) {
        for (PacketHandler handler : packetHandlers) {
            try {
                if (!handler.handlePacket(p, channel, packet, direction)) {
                    return false;
                }
            } catch (Exception ex) {
                String message = String.format("Error on packet %s from %s with direction %s", packet.getClass().getSimpleName(), p.getName(), direction);

                plugin.getLogger().log(Level.SEVERE, message, ex);
            }
        }
        return true;
    }

    public interface PacketHandler {

        boolean handlePacket(Player player, Channel channel, Object packet, PacketDirection direction);
    }

    public enum PacketDirection {

        IN, OUT

    }
}

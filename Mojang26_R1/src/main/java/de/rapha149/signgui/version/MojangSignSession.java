package de.rapha149.signgui.version;

import de.rapha149.signgui.SignEditor;
import de.rapha149.signgui.SignGUIChannelHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Shared lifecycle for both 26.x sign APIs. Bukkit/NMS runs on the server thread;
 * pipeline changes run on the channel event loop, including disconnect cleanup. */
final class MojangSignSession extends SignGUIChannelHandler<Packet<?>> {
    private static final AttributeKey<MojangSignSession> CURRENT = AttributeKey.valueOf(MojangSignSession.class.getName()
            + "@" + Integer.toHexString(System.identityHashCode(MojangSignSession.class)));
    private final Player player;
    private final SignEditor editor;
    private final Function<Packet<?>, String[]> read;
    private final BiConsumer<SignEditor, String[]> finish;
    private final AtomicBoolean submitted = new AtomicBoolean();

    private MojangSignSession(Player player, SignEditor editor, Function<Packet<?>, String[]> read,
                              BiConsumer<SignEditor, String[]> finish) {
        this.player = player;
        this.editor = editor;
        this.read = read;
        this.finish = finish;
    }

    static void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else MinecraftServer.getServer().executeIfPossible(task);
    }

    private static MojangSignSession current(SignEditor editor) {
        return editor.getPipeline().channel().attr(CURRENT).get();
    }

    static boolean isCurrent(Player player, SignEditor editor) {
        MojangSignSession session = current(editor);
        return session != null && session.editor == editor && player.isOnline()
                && editor.getPipeline().channel().isActive() && player.getWorld().equals(editor.getLocation().getWorld());
    }

    static void open(Player player, BooleanSupplier connected, SignEditor editor, Runnable send,
                     Function<Packet<?>, String[]> read, BiConsumer<SignEditor, String[]> finish) {
        ChannelPipeline pipeline = editor.getPipeline();
        if (!connected.getAsBoolean() || !pipeline.channel().isActive()) return;
        MojangSignSession previous = current(editor);
        boolean delay = previous != null && previous.getBlockPosition().equals(editor.getBlockPosition());
        if (previous != null) previous.close();
        MojangSignSession session = new MojangSignSession(player, editor, read, finish);
        pipeline.channel().attr(CURRENT).set(session); // Invalidates even an opening still waiting for its delay.
        Runnable install = () -> onMain(() -> {
            if (!isCurrent(player, editor) || !connected.getAsBoolean()) return;
            onChannel(pipeline, () -> {
                if (current(editor) != session || !connected.getAsBoolean() || pipeline.get("decoder") == null) return;
                ChannelHandler old = pipeline.get("SignGUI");
                if (old != null) pipeline.remove(old);
                pipeline.addAfter("decoder", "SignGUI", session);
                onMain(() -> {
                    if (isCurrent(player, editor) && connected.getAsBoolean()) send.run();
                });
            });
        });
        // Clear a pending opening too: it has no handler to receive channelInactive yet.
        pipeline.channel().closeFuture().addListener(ignored -> pipeline.channel().attr(CURRENT).compareAndSet(session, null));
        if (delay) later(install);
        else install.run();
    }

    static void later(Runnable task) {
        VersionWrapper.SCHEDULER.schedule(task, 200, TimeUnit.MILLISECONDS);
    }

    private static void onChannel(ChannelPipeline pipeline, Runnable task) {
        try {
            if (pipeline.channel().eventLoop().inEventLoop()) task.run();
            else pipeline.channel().eventLoop().execute(task);
        } catch (RejectedExecutionException ignored) {
            // The channel event loop has already shut down after disconnect.
        }
    }

    static void close(Player player, SignEditor editor) {
        onMain(() -> {
            MojangSignSession session = current(editor);
            // onFinish may already have opened another GUI. Never close or redraw that session.
            if (session == null || session.editor != editor) return;
            editor.getPipeline().channel().attr(CURRENT).compareAndSet(session, null);
            onChannel(editor.getPipeline(), () -> {
                if (editor.getPipeline().context(session) != null) editor.getPipeline().remove(session);
            });
            Location loc = editor.getLocation();
            if (player.isOnline() && editor.getPipeline().channel().isActive() && player.getWorld().equals(loc.getWorld()))
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
        });
    }

    static void acceptNext(SignEditor editor) {
        MojangSignSession session = current(editor);
        if (session != null && session.editor == editor) session.submitted.set(false);
    }

    @Override
    public Object getBlockPosition() {
        return editor.getBlockPosition();
    }

    @Override
    public void close() {
        close(player, editor);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Packet<?> packet, List<Object> out) {
        try {
            String[] lines = current(editor) == this ? read.apply(packet) : null;
            if (lines != null && submitted.compareAndSet(false, true)) finish.accept(editor, lines);
        } catch (Exception exception) {
            exception.printStackTrace();
        } finally {
            out.add(packet);
        }
    }
}

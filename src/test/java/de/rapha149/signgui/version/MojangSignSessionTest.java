package de.rapha149.signgui.version;

import de.rapha149.signgui.SignEditor;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MojangSignSessionTest {
    /** Captures main-thread work explicitly; no live server or scheduler is needed. */
    private static final class Fixture implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final Player player = mock(Player.class);
        final AtomicBoolean connected = new AtomicBoolean(true);
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final BlockData data = mock(BlockData.class);
        final AtomicInteger sends = new AtomicInteger();
        final List<Runnable> main = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        final MockedStatic<MojangSignSession> dispatch = mockStatic(MojangSignSession.class, inv -> {
            switch (inv.getMethod().getName()) {
                case "onMain" -> main.add(inv.getArgument(0));
                case "later" -> delayed.add(inv.getArgument(0));
                default -> { return inv.callRealMethod(); }
            }
            return null;
        });

        Fixture() {
            channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(block.getBlockData()).thenReturn(data);

        }

        SignEditor editor(int x) {
            return new SignEditor(new Object(), new Location(world, x, 64, 0), x, channel.pipeline());
        }

        void open(SignEditor editor) {
            MojangSignSession.open(player, connected::get, editor, sends::incrementAndGet, packet -> null, (e, lines) -> {});
        }

        void drain() {
            while (!main.isEmpty()) main.removeFirst().run();
            channel.runPendingTasks();
        }

        @Override public void close() {
            channel.finishAndReleaseAll();
            dispatch.close();
        }
    }

    @Test
    void queuedOpeningCannotReplaceNewerSessionOrDuplicateHandler() {
        try (Fixture f = new Fixture()) {
            SignEditor first = f.editor(1);
            SignEditor second = f.editor(2);
            f.open(first);
            f.open(second); // first install is still waiting for the server thread
            f.drain();
            assertEquals(1, f.sends.get());
            assertTrue(MojangSignSession.isCurrent(f.player, second));
            assertNotNull(f.channel.pipeline().get("SignGUI"));
            MojangSignSession.close(f.player, first); // old onFinish must not close the new editor
            f.drain();
            assertTrue(MojangSignSession.isCurrent(f.player, second));
            assertNotNull(f.channel.pipeline().get("SignGUI"));
        }
    }

    @Test
    void delayedReopeningCannotReplaceThirdOpeningAtTheSamePosition() {
        try (Fixture f = new Fixture()) {
            f.open(f.editor(1));
            f.drain();
            SignEditor second = f.editor(1);
            f.open(second);
            f.drain();
            SignEditor third = f.editor(1);
            f.open(third);
            f.drain();
            assertEquals(2, f.delayed.size());
            for (Runnable task : f.delayed) task.run();
            f.drain();
            assertEquals(2, f.sends.get()); // initial editor and latest reopening only
            assertTrue(MojangSignSession.isCurrent(f.player, third));
            assertNotNull(f.channel.pipeline().get("SignGUI"));
        }
    }

    @Test
    void lostDecoderOrDisconnectBeforeInstallIsHarmless() {
        for (boolean disconnect : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                f.open(f.editor(1));
                if (disconnect) {
                    when(f.player.isOnline()).thenReturn(false);
                    f.connected.set(false);
                    f.channel.close();
                } else f.channel.pipeline().remove("decoder");
                assertDoesNotThrow(f::drain);
                assertEquals(0, f.sends.get());
                assertNull(f.channel.pipeline().get("SignGUI"));
            }
        }
    }

    @Test
    void closeWithoutHandlerIsIdempotentAndRestoresBlockOnlyInMainTask() {
        try (Fixture f = new Fixture()) {
            SignEditor editor = f.editor(1);
            f.open(editor);
            f.drain();
            f.channel.pipeline().remove("SignGUI"); // channel teardown already removed it
            MojangSignSession.close(f.player, editor);
            verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            verify(f.world, never()).getBlockAt(any(Location.class));
            assertDoesNotThrow(f::drain);
            verify(f.player).sendBlockChange(editor.getLocation(), f.data);
            MojangSignSession.close(f.player, editor);
            assertDoesNotThrow(f::drain);
            verify(f.player, times(1)).sendBlockChange(any(Location.class), any(BlockData.class));
        }
    }

    @Test
    void repeatedUpdateIsDeliveredOnceUntilNewLinesAreDisplayed() {
        try (Fixture f = new Fixture()) {
            SignEditor editor = f.editor(1);
            AtomicInteger callbacks = new AtomicInteger();
            MojangSignSession.open(f.player, f.connected::get, editor, f.sends::incrementAndGet,
                    packet -> new String[]{"price"}, (e, lines) -> callbacks.incrementAndGet());
            f.drain();
            Packet<?> packet = mock(Packet.class);
            f.channel.writeInbound(packet, packet);
            assertEquals(1, callbacks.get());
            MojangSignSession.acceptNext(editor);
            f.channel.writeInbound(packet);
            assertEquals(2, callbacks.get());
        }
    }
}

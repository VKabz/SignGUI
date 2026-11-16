package de.rapha149.signgui.version;

import de.rapha149.signgui.SignEditor;
import io.papermc.paper.adventure.AdventureComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

public class MojangWrapper26_R1 implements VersionWrapper {

    @Override
    public Material getDefaultType() {
        return Material.OAK_WALL_SIGN;
    }

    @Override
    public List<Material> getSignTypes() {
        return Arrays.asList(
                Material.OAK_SIGN, Material.BIRCH_SIGN, Material.SPRUCE_SIGN, Material.JUNGLE_SIGN,
                Material.ACACIA_SIGN, Material.DARK_OAK_SIGN, Material.CRIMSON_SIGN, Material.WARPED_SIGN,
                Material.CHERRY_SIGN, Material.MANGROVE_SIGN, Material.BAMBOO_SIGN, Material.PALE_OAK_SIGN,
                Material.OAK_WALL_SIGN, Material.BIRCH_WALL_SIGN, Material.SPRUCE_WALL_SIGN, Material.JUNGLE_WALL_SIGN,
                Material.ACACIA_WALL_SIGN, Material.DARK_OAK_WALL_SIGN, Material.CRIMSON_WALL_SIGN, Material.WARPED_WALL_SIGN,
                Material.CHERRY_WALL_SIGN, Material.MANGROVE_WALL_SIGN, Material.BAMBOO_WALL_SIGN, Material.PALE_OAK_WALL_SIGN
        );
    }

    private static Component[] createLines(String[] textLines, Object[] adventureLines) {
        Component[] lines = new Component[4];
        Arrays.fill(lines, Component.empty());
        if (adventureLines != null) {
            for (int i = 0; i < adventureLines.length; i++) {
                Object line = adventureLines[i];
                if (line instanceof net.kyori.adventure.text.Component component) {
                    lines[i] = new AdventureComponent(component);
                } else if (line == null) {
                    lines[i] = Component.empty();
                } else {
                    throw new IllegalArgumentException("line at index " + i + " is not net.kyori.adventure.text.Component");
                }
            }
        } else {
            for (int i = 0; i < textLines.length; i++)
                lines[i] = Component.nullToEmpty(textLines[i]);
        }
        return lines;
    }

    @Override
    public void openSignEditor(Player player, String[] textLines, Object[] adventureLines, Material type, DyeColor color, boolean glow, Location signLoc, BiConsumer<SignEditor, String[]> onFinish) {
        MojangSignSession.onMain(() -> {
            if (!player.isOnline()) return;
            ServerPlayer p = ((CraftPlayer) player).getHandle();
            ServerGamePacketListenerImpl conn = p.connection;

            Location loc = signLoc != null ? signLoc : getDefaultLocation(player);
            BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

            SignBlockEntity sign = new SignBlockEntity(pos, Blocks.OAK_SIGN.defaultBlockState());
            SignText.Mutable signText = sign.getText(SignTextSlot.FRONT).asMutable()
                    .setColor(net.minecraft.world.item.DyeColor.valueOf(color.toString()))
                    .setTextGlowing(glow);

            Component[] lines = createLines(textLines, adventureLines);
            for (int i = 0; i < lines.length; i++)
                signText.setLine(i, lines[i]);
            sign.setText(signText.asImmutable(), SignTextSlot.FRONT);

            Connection manager = conn.connection;
            SignEditor editor = new SignEditor(sign, loc, pos, manager.channel.pipeline());
            MojangSignSession.open(player, manager::isConnected, editor, () -> {
                player.sendBlockChange(loc, type.createBlockData());
                sign.setLevel(p.level());
                conn.send(sign.getUpdatePacket());
                sign.setLevel(null);
                conn.send(new ClientboundOpenSignEditorPacket(pos, SignTextSlot.FRONT));
            }, packet -> packet instanceof ServerboundSignUpdatePacket update
                    && update.pos().equals(pos) && update.slot() == SignTextSlot.FRONT
                    ? update.lines().toArray(new String[0]) : null, onFinish);
        });
    }

    @Override
    public void displayNewLines(Player player, SignEditor signEditor, String[] textLines, Object[] adventureLines) {
        MojangSignSession.onMain(() -> {
            if (!MojangSignSession.isCurrent(player, signEditor)) return;
            SignBlockEntity sign = (SignBlockEntity)signEditor.getSign();

            SignText.Mutable newSignText = sign.getText(SignTextSlot.FRONT).asMutable();
            Component[] lines = createLines(textLines, adventureLines);
            for (int i = 0; i < lines.length; ++i)
                newSignText.setLine(i, lines[i]);
            sign.setText(newSignText.asImmutable(), SignTextSlot.FRONT);

            ServerPlayer p = ((CraftPlayer)player).getHandle();
            ServerGamePacketListenerImpl conn = p.connection;
            sign.setLevel(p.level());
            conn.send(sign.getUpdatePacket());
            sign.setLevel(null);
            conn.send(new ClientboundOpenSignEditorPacket((BlockPos)signEditor.getBlockPosition(), SignTextSlot.FRONT));
            MojangSignSession.acceptNext(signEditor);
        });
    }

    @Override
    public void closeSignEditor(Player player, SignEditor signEditor) {
        MojangSignSession.close(player, signEditor);
    }
}

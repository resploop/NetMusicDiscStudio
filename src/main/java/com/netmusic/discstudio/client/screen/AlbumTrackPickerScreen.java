package com.netmusic.discstudio.client.screen;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.netmusic.discstudio.network.DiscStudioTrackPacket;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldButton;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 「网络CD」在现代化唱片机上的选曲面板。
 * <p>
 * <b>只读</b>：这里只能切歌，不能改内容——曲目表是刻录机刻进去的。
 * 列表内容直接读唱片机槽位里唱片自己的 {@code album_playlist} 组件，
 * 而唱片机的方块实体更新包会把这个组件同步到客户端，所以不需要额外的下行协议。
 */
public class AlbumTrackPickerScreen extends DiscStudioScreen {
    private static final int FIELD_H = 18;
    private static final int ROWS = 7;
    private static final int ROW_H = 13;
    private static final int LIST_TOP = HEADER_H + 66;
    private static final int BOX_HEIGHT = LIST_TOP + ROWS * ROW_H + 5;

    private int listPage;
    private String lastSourceId = "";

    public AlbumTrackPickerScreen(BlockPos pos) {
        super(Component.translatable("gui.netmusic_disc_studio.picker.title"), pos);
    }

    @Override
    protected int boxH() {
        return BOX_HEIGHT;
    }

    @Override
    protected void buildWidgets() {
        int bx = boxX();
        int by = boxY();
        int inner = BOX_W - PAD * 2;
        int half = (inner - 8) / 2;
        int quarter = (inner - 16) / 3;

        addRenderableWidget(new BlackGoldButton(bx + PAD, by + HEADER_H + 24, half, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.track.prev"),
                button -> sendTrack(DiscStudioTrackPacket.TrackAction.PREV), TEXT_PRIMARY));
        addRenderableWidget(new BlackGoldButton(bx + PAD + half + 8, by + HEADER_H + 24, half, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.track.next"),
                button -> sendTrack(DiscStudioTrackPacket.TrackAction.NEXT), TEXT_PRIMARY));

        addRenderableWidget(new BlackGoldButton(bx + PAD, by + HEADER_H + 46, quarter, FIELD_H - 2,
                Component.translatable("gui.netmusic_disc_studio.page.prev"),
                button -> listPage = Math.max(0, listPage - 1), TEXT_SECONDARY));
        addRenderableWidget(new BlackGoldButton(bx + PAD + (quarter + 8) * 2, by + HEADER_H + 46, quarter,
                FIELD_H - 2, Component.translatable("gui.netmusic_disc_studio.page.next"),
                button -> listPage = listPage + 1, TEXT_SECONDARY));
    }

    @Override
    protected void onSave() {
    }

    private void sendTrack(DiscStudioTrackPacket.TrackAction action) {
        sendPacket(new DiscStudioTrackPacket(blockPos, action, 0));
    }

    private void selectTrack(int index) {
        sendPacket(new DiscStudioTrackPacket(blockPos, DiscStudioTrackPacket.TrackAction.SELECT, index));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean cancelled) {
        if (!cancelled && clickTrackRow(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, cancelled);
    }

    private boolean clickTrackRow(double mouseX, double mouseY) {
        AlbumPlaylist playlist = NetworkDiscs.playlist(currentDisc());
        if (playlist == null || playlist.isEmpty()) {
            return false;
        }
        int bx = boxX();
        int by = boxY();
        if (mouseX < bx + PAD || mouseX > bx + BOX_W - PAD) {
            return false;
        }
        int first = listPage * ROWS;
        for (int row = 0; row < ROWS; row++) {
            int index = first + row;
            if (index >= playlist.size()) {
                break;
            }
            int rowY = by + LIST_TOP + row * ROW_H;
            if (mouseY >= rowY && mouseY < rowY + ROW_H) {
                selectTrack(index);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        int cx = bx + BOX_W / 2;
        int inner = BOX_W - PAD * 2;

        AlbumPlaylist playlist = NetworkDiscs.playlist(currentDisc());
        if (playlist == null || playlist.isEmpty()) {
            g.centeredText(font, Component.translatable("gui.netmusic_disc_studio.picker.empty"),
                    cx, by + HEADER_H + 24, TEXT_DIM);
            return;
        }

        if (!playlist.sourceId().equals(lastSourceId)) {
            lastSourceId = playlist.sourceId();
            listPage = 0;
        }
        int pages = Math.max(1, (playlist.size() + ROWS - 1) / ROWS);
        listPage = Math.clamp(listPage, 0, pages - 1);

        String title = playlist.title().isBlank() ? playlist.sourceId() : playlist.title();
        String header = title + "  " + (playlist.selectedIndex() + 1) + "/" + playlist.size()
                + "  (" + (listPage + 1) + "/" + pages + ")";
        g.centeredText(font, Component.literal(BlackGoldUi.ellipsize(font, header, inner)),
                cx, by + HEADER_H + 6, GOLD);

        int first = listPage * ROWS;
        for (int row = 0; row < ROWS; row++) {
            int index = first + row;
            if (index >= playlist.size()) {
                break;
            }
            ItemMusicCD.SongInfo track = playlist.tracks().get(index);
            boolean selected = index == playlist.selectedIndex();
            int rowY = by + LIST_TOP + row * ROW_H;
            if (selected) {
                g.fill(bx + PAD - 2, rowY - 1, bx + BOX_W - PAD + 2, rowY + ROW_H - 1, 0x30D4A843);
            }
            String artists = track.artists == null || track.artists.isEmpty()
                    ? ""
                    : "  · " + String.join("/", track.artists);
            String label = (index + 1) + ". " + (track.songName == null ? "" : track.songName) + artists;
            g.text(font, Component.literal(BlackGoldUi.ellipsize(font, label, inner - 4)),
                    bx + PAD, rowY, selected ? GOLD : TEXT_SECONDARY, false);
        }
    }
}

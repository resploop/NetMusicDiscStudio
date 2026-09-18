package com.netmusic.discstudio;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置。
 * <p>
 * 配置项通过 {@link ModConfigEvent} 读入静态字段，业务代码只读静态字段，
 * 避免每次访问都走 ConfigValue 的锁。
 */
public final class DiscStudioConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ALLOW_NETEASE_DIRECT = BUILDER
            .comment("Allow NetEase tracks marked as VIP to be played on the modern turntable.",
                    "The turntable refuses VIP songs unless some resolver claims them, while NetMusic's own",
                    "music player has no such gate. Keeping this on matches upstream behaviour.",
                    "Turn it off if your server must respect NetEase VIP gating strictly.")
            .define("allowNetEaseDirectPlayback", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean allowNetEaseDirectPlayback = true;

    private DiscStudioConfig() {
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        allowNetEaseDirectPlayback = ALLOW_NETEASE_DIRECT.get();
    }

    public static boolean allowNetEaseDirectPlayback() {
        return allowNetEaseDirectPlayback;
    }
}

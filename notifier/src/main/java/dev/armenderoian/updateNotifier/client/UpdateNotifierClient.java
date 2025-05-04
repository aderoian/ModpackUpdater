package dev.armenderoian.updateNotifier.client;

import dev.armenderoian.updateNotifier.UpdateNotifier;
import dev.armenderoian.updateNotifier.client.gui.UpdateNotificationScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.TitleScreen;

public class UpdateNotifierClient implements ClientModInitializer {

    private static boolean firstScreenShown = false;

    @Override
    public void onInitializeClient() {
        var update = UpdateNotifier.checkForUpdates();
        if (update != null) {
            ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
                if (!firstScreenShown && screen instanceof TitleScreen) {
                    firstScreenShown = true;
                    var newScreen = new UpdateNotificationScreen(screen, update);
                    client.setScreen(newScreen);
                }
            });
        }
    }
}

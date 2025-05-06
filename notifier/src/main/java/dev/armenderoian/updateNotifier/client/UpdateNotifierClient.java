package dev.armenderoian.updateNotifier.client;

import dev.armenderoian.updateNotifier.UpdateNotifier;
import dev.armenderoian.updateNotifier.client.gui.UpdateNotificationScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.TitleScreen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UpdateNotifierClient implements ClientModInitializer {

    private static boolean firstScreenShown = false;

    @Override
    public void onInitializeClient() {
        Path lockFile = Paths.get("update.lock");
        if (Files.exists(lockFile)) {
            try {
                String content = Files.readString(lockFile).trim();
                long pid = Long.parseLong(content);

                boolean isRunning = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);

                if (isRunning) {
                    System.err.println("[UpdateBlocker] Updater still running (PID " + pid + "). Blocking startup.");
                    System.exit(1);
                } else {
                    System.err.println("[UpdateBlocker] Stale PID lock file (PID " + pid + "). Deleting and continuing.");
                    Files.delete(lockFile);
                }
            } catch (Exception e) {
                System.err.println("[UpdateBlocker] Invalid or unreadable update.lock. Deleting just in case.");
                try {
                    Files.delete(lockFile);
                } catch (IOException ignore) {}
            }
        }

        if (UpdateNotifier.checkForUpdates()) {
            ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
                if (!firstScreenShown && screen instanceof TitleScreen) {
                    firstScreenShown = true;
                    var newScreen = new UpdateNotificationScreen(screen, UpdateNotifier.getLatestUpdate(UpdateNotifier.CONFIG.getCurrentVersion().getVersion()));
                    client.setScreen(newScreen);
                }
            });
        }
    }
}

package dev.armenderoian.updateNotifier.client;

import dev.armenderoian.updateNotifier.UpdateNotifier;
import net.fabricmc.api.ClientModInitializer;

public class UpdateNotifierClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        UpdateNotifier.checkForUpdates();
    }
}

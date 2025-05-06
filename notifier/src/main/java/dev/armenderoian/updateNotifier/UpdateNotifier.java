package dev.armenderoian.updateNotifier;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.armenderoian.modpack.update.models.Response;
import net.armenderoian.modpack.update.models.Update;
import net.armenderoian.modpack.update.models.UpdateMeta;
import net.armenderoian.modpack.update.util.Config;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class UpdateNotifier implements ModInitializer {

    public static final String MOD_ID = "update-notifier";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static Config CONFIG;

    private static final Gson GSON = new Gson();

    @Override
    public void onInitialize() {
        CONFIG = Config.load(FabricLoader.getInstance().getConfigDir());

        ClientLifecycleEvents.CLIENT_STOPPING.register(server -> {
            if (CONFIG != null) {
                Config.save(FabricLoader.getInstance().getConfigDir(), CONFIG);
            }
        });
    }

    public static boolean checkForUpdates() {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create(CONFIG.getUpdateUrl() + "/" + CONFIG.getChannel() + "/latest"))
                    .build(), HttpResponse.BodyHandlers.ofString());

            if (response == null || response.statusCode() != 200) {
                LOGGER.error("[ERROR FETCHING]: Failed to fetch the latest version.");
                return false;
            }

            var responseData = GSON.fromJson(response.body(), new TypeToken<Response<UpdateMeta>>() {});

            var status = responseData.getCode();
            if (status != 200) {
                LOGGER.error("[ERROR FETCHING]: ({}) {}", status, responseData.getMessage());
                return false;
            } else {
                try {
                    var update = responseData.getData();
                    if (update == null) {
                        LOGGER.error("[MALFORMED RESPONSE]: Update meta not found.");
                        return false;
                    }

                    // Check if the current version is less than the latest version
                    if (CONFIG.getCurrentVersion().compareTo(update) < 0) {
                        LOGGER.info("There is an update available: v{} -> v{}", CONFIG.getCurrentVersion().getVersion(), update.getVersion());
                        return true;
                    } else {
                        LOGGER.info("You are using the latest version.");
                        return false;
                    }
                } catch (Exception e) {
                    LOGGER.error("[MALFORMED RESPONSE]: Failed to parse 'meta' field.", e);
                    return false;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ERROR FETCHING]: failed to send HTTP request. ", e);
            return false;
        }
    }

    public static Update getLatestUpdate(String currentVersion) {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create(CONFIG.getUpdateUrl() + "/" + CONFIG.getChannel() + "/getFull/" + currentVersion))
                    .build(), HttpResponse.BodyHandlers.ofString());

            if (response == null || response.statusCode() != 200) {
                LOGGER.error("[ERROR FETCHING]: Failed to fetch the latest update.");
                return null;
            }

            var responseData = GSON.fromJson(response.body(), new TypeToken<Response<Update>>() {});

            var status = responseData.getCode();
            if (status != 200) {
                LOGGER.error("[ERROR FETCHING]: ({}) {}", status, responseData.getMessage());
                return null;
            } else {
                try {
                    var update = responseData.getData();
                    if (update == null) {
                        LOGGER.error("[MALFORMED RESPONSE]: Empty 'meta' field in response.");
                        return null;
                    }

                    // Check if the current version is less than the latest version
                    if (CONFIG.getCurrentVersion().compareTo(update.getMeta()) < 0) {
                        return update;
                    } else {
                        return null;
                    }
                } catch (Exception e) {
                    LOGGER.error("[MALFORMED RESPONSE]: Failed to parse response", e);
                    return null;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ERROR FETCHING]: failed to send HTTP request. ", e);
            return null;
        }
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}

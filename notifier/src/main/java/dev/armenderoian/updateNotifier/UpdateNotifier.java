package dev.armenderoian.updateNotifier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.armenderoian.updateNotifier.util.Config;
import net.armenderoian.modpack.update.models.UpdateMeta;
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

    public static void checkForUpdates() {
        if (CONFIG.getCurrentVersion().getVersion().equals("0.0.0")) {
            LOGGER.warn("[UPDATE NOTIFIER]: The current version is not set. Please set the current version in the config file.");
            return;
        }

        try (var client = HttpClient.newHttpClient()) {
            client.sendAsync(HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create(CONFIG.getUpdateUrl() + "/latest"))
                    .build(), HttpResponse.BodyHandlers.ofString()
            ).thenApply(HttpResponse::body).thenAccept(body -> {
                // Load the response body into a JsonObject
                var response = GSON.fromJson(body, JsonObject.class);

                // Ensure the response is valid
                if (!response.has("status")){
                    LOGGER.error("[MALFORMED RESPONSE]: Missing 'status' field in response.");
                    return;
                }

                // Check the status code
                var status = response.get("status").getAsInt();
                if (status != 200) {
                    LOGGER.error("[ERROR FETCHING]: ({}) {}", status, response.get("message").getAsString());
                } else {
                    // Check if the response contains the expected fields
                    if (!response.has("meta")) {
                        LOGGER.error("[MALFORMED RESPONSE]: Missing 'meta' field in response.");
                        return;
                    }

                    try {
                        var meta = GSON.fromJson(response.get("meta"), UpdateMeta.class);
                        if (meta == null) {
                            LOGGER.error("[MALFORMED RESPONSE]: Empty 'meta' field in response.");
                            return;
                        }

                        // Check if the current version is less than the latest version
                        if (CONFIG.getCurrentVersion().compareTo(meta) < 0) {
                            LOGGER.info("There is an update available: v{} -> v{}", CONFIG.getCurrentVersion().getVersion(), meta.getVersion());
                        } else {
                            LOGGER.info("You are using the latest version.");
                        }
                    } catch (Exception e) {
                        LOGGER.error("[MALFORMED RESPONSE]: Failed to parse 'meta' field.", e);
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[ERROR FETCHING]: failed to send HTTP request. ", e);
        }
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}

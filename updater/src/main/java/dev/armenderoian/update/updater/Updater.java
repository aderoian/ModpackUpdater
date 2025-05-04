package dev.armenderoian.update.updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.armenderoian.modpack.update.models.Response;
import net.armenderoian.modpack.update.models.Update;
import net.armenderoian.modpack.update.models.UpdateEntry;
import net.armenderoian.modpack.update.models.UpdateMeta;
import net.armenderoian.modpack.update.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Updater {

    public static String VERSION_TAG = "{{version}}";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger logger = LoggerFactory.getLogger(Updater.class);

    public static void main(String[] args) {
        logger.info("Starting ModPack Updater...");

        var configPath = Path.of("./config");
        Config config = Config.load(configPath);
        if (configPath.toFile().exists()) Config.save(configPath, config);
        logger.info("Loaded config: {}", configPath.toAbsolutePath());

        UpdateMeta currentVersion = config.getCurrentVersion();
        logger.info("Current version: v{}", currentVersion.getVersion());

        String channel = config.getChannel();
        UpdateMeta latest;
        Update update;

        try (var client = HttpClient.newHttpClient()) {
            var response = GSON.fromJson(client.send(HttpRequest.newBuilder().GET().uri(URI.create(config.getUpdateUrl() + "/" + channel + "/latest")).build(),
                    HttpResponse.BodyHandlers.ofString()).body(), new TypeToken<Response<UpdateMeta>>() {});
            if (response.getCode() != 200 || response.getData() == null) {
                logger.error("Failed to fetch latest version: {}", response.getMessage());
                return;
            }

            latest = response.getData();
            logger.info("Latest version: v{}", latest.getVersion());

            if (latest.getVersion().equals(currentVersion.getVersion())) {
                logger.info("You are already on the latest version.");
                return;
            } else {
                logger.info("A new version is available! Version: v{} - {}", latest.getVersion(), latest.getDescription());
            }

            logger.info("Fetching update manifest...");
            var manifest = GSON.fromJson(client.send(HttpRequest.newBuilder().GET().uri(URI.create(config.getUpdateUrl() + "/" + channel + "/getFull/" + currentVersion.getVersion())).build(),
                    HttpResponse.BodyHandlers.ofString()).body(), new TypeToken<Response<Update>>() {});
            if (manifest.getCode() != 200 || manifest.getData() == null) {
                logger.error("Failed to fetch update manifest: \n{}", manifest.getMessage());
                return;
            }

            update = manifest.getData();
            logger.info("Update manifest: {}", GSON.toJson(update));

            logger.info("Starting update to: v{}", latest.getVersion());
            var updatePath = Path.of("./mods");
            updatePath.toFile().mkdirs();

            int curr = 0;
            int total = update.getEntries().length;

            List<String> mods;
            try (var fileStream = Files.list(updatePath)) {
                mods = fileStream.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList();
            }

            String updatedModFilename;
            String oldModFilename;
            for (var entry : update.getEntries()) {
                logger.info("Updating mod [{}/{}]: {}", curr++, total, entry.getName());

                updatedModFilename = entry.getFileName();

                var firstPart = updatedModFilename.substring(0, updatedModFilename.indexOf(VERSION_TAG));
                var lastPart = updatedModFilename.substring(updatedModFilename.indexOf(VERSION_TAG) + VERSION_TAG.length());

                oldModFilename = mods.stream().filter(m -> m.startsWith(firstPart) && m.endsWith(lastPart)).findFirst().orElse(null);
                if (entry.getType() == UpdateEntry.Type.REMOVED && oldModFilename == null) {
                    logger.error("Failed to find old mod file for: {}", updatedModFilename);
                    continue;
                }

                if (entry.getType() != UpdateEntry.Type.ADDED && oldModFilename != null) {
                    try {
                        logger.info("Deleting mod file: {}", oldModFilename);
                        Files.delete(updatePath.resolve(oldModFilename));
                    } catch (IOException e) {
                        logger.error("Failed to delete old mod file: {}", oldModFilename, e);
                    }

                    if (entry.getType() == UpdateEntry.Type.REMOVED) {
                        logger.info("Mod file removed: {}", oldModFilename);
                        continue;
                    }
                }

                updatedModFilename = updatedModFilename.replace(VERSION_TAG, entry.getVersion());

                logger.info("Downloading: {} -> {}", entry.getDownloadUrl(), updatedModFilename);
                var downloadResponse = client.send(HttpRequest.newBuilder().GET().uri(URI.create(entry.getDownloadUrl())).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (downloadResponse.statusCode() != 200) {
                    logger.error("Failed to download file: {}", downloadResponse.body());
                    continue;
                }

                try {
                    var file = updatePath.resolve(updatedModFilename);
                    Files.write(file, downloadResponse.body());
                    logger.info("Downloaded: {} -> {}", entry.getDownloadUrl(), file);
                } catch (IOException e) {
                    logger.error("Failed to write file: {}", updatedModFilename, e);
                }
            }

            config.setCurrentVersion(latest);
            Config.save(configPath, config);

            logger.info("Update complete!");
        } catch (IOException | InterruptedException e) {
            logger.error("Failed while updating", e);
        }
    }
}

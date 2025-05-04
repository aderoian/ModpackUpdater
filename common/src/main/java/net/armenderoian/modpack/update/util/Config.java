package net.armenderoian.modpack.update.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;
import net.armenderoian.modpack.update.models.UpdateMeta;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Serializable;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;

@Getter
public final class Config implements Serializable {

    public static String NAME = "update-notifier.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private String updateUrl;
    @Setter
    private UpdateMeta currentVersion;

    public static Config createDefault() {
        Config config = new Config();
        config.updateUrl = "";
        config.currentVersion = new UpdateMeta(
                "Fresh Install",
                "0.0.0",
                "This instance has not been configured. Please configure the update URL to start fetching update data.",
                DateFormat.getDateInstance().format(new Date())
        );
        return config;
    }

    public static Config load(Path path) {
        path.toFile().mkdirs();

        Path file = path.resolve(NAME);
        if (!file.toFile().exists()) {
            return createDefault();
        }

        try (var reader = new FileReader(file.toFile())) {
            return GSON.fromJson(reader, Config.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: ", e);
        }
    }

    public static void save(Path path, Config config) {
        path.toFile().mkdirs();

        Path file = path.resolve(NAME);
        try (var writer = new FileWriter(file.toFile())) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save config: ", e);
        }
    }
}
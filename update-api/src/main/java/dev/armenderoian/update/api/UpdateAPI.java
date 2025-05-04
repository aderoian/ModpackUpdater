package dev.armenderoian.update.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinGson;
import net.armenderoian.modpack.update.models.Response;
import net.armenderoian.modpack.update.models.Update;
import net.armenderoian.modpack.update.models.UpdateEntry;
import net.armenderoian.modpack.update.models.UpdateMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class UpdateAPI {

    private static final Logger logger = LoggerFactory.getLogger(UpdateAPI.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Javalin webApp = Javalin.create(config -> config.jsonMapper(new JavalinGson()));
    private static Path updatesDir;
    private static int port;
    private static String apiKey;

    private static final Map<String, Update> updateCache = new HashMap<>();
    private static UpdateMeta latest;
    private static List<UpdateMeta> versions = null;

    public static void main(String[] args) throws InterruptedException {
        logger.info("Starting Update API...");

        updatesDir = Path.of("./").resolve("updates");

        String arg;
        for (int i = 0; i < args.length; i++) {
            arg = args[i];
            if (arg.equalsIgnoreCase("--port")) {
                if (i + 1 >= args.length) {
                    logger.error("Port not specified");
                    return;
                }
                port = Integer.parseInt(args[++i]);
                logger.info("Using port: {}", port);
            } else if (arg.equalsIgnoreCase("--updates-dir")) {
                var path = args[++i];
                if (path.startsWith("\"")) {
                    while (!path.endsWith("\"")) {
                        path += " " + args[++i];
                    }
                }
                updatesDir = Path.of(path.replace("\"", ""));
                logger.info("Using updates directory: {}", updatesDir.toAbsolutePath());
            } else if (arg.equalsIgnoreCase("--api-key")) {
                if (i + 1 >= args.length) {
                    logger.error("API key not specified");
                    return;
                }
                apiKey = args[++i];
                logger.info("Using API key: {}", apiKey);
            }
        }

        if (!updatesDir.toFile().mkdirs()) {
            logger.warn("Failed to create directory: " + updatesDir.toAbsolutePath());
        }

        configureWebApp();

        var javalinThread = new Thread(() -> webApp.start(port));
        javalinThread.setName("javalin-thread");
        javalinThread.start();

        Thread.sleep(1_000);
        var scanner = new Scanner(System.in);
        logger.info("Type 'stop' to stop the server...");

        String line;
        while (true) {
            if (scanner.hasNextLine()) {
                line = scanner.nextLine();
                if (line.equalsIgnoreCase("stop")) {
                    break;
                }
            }

            Thread.sleep(100);
        }

        logger.info("Stopping Update API...");
        stop();
        javalinThread.interrupt();
        try {
            javalinThread.join();
        } catch (InterruptedException e) {
            logger.error("Failed to stop Javalin thread");
        }
    }

    private static void stop() {
        webApp.stop();
    }

    private static void configureWebApp() {
        webApp.get("/latest", UpdateAPI::handleLatestUpdateRequest);
        webApp.get("/get/{version}", UpdateAPI::handleGetUpdateRequest);
        webApp.get("/getFull/{version}", UpdateAPI::handleGetFullUpdateRequest);
        webApp.post("/create", UpdateAPI::handleCreateUpdateRequest);

    }

    private static void handleLatestUpdateRequest(Context ctx) {
        if (latest != null) {
            sendResponse(ctx, 200, null, latest);
            return;
        }

        var latestUpdate = updatesDir.resolve("latest.json");
        if (!latestUpdate.toFile().exists()) {
            sendResponse(ctx, 404, "Latest update not found", null);
        } else {
            try (var reader = new BufferedReader(new FileReader(latestUpdate.toFile()))) {
                var update = gson.fromJson(reader, UpdateMeta.class);
                if (update == null) {
                    sendResponse(ctx, 404, "Latest update not found", null);
                } else {
                    latest = update;
                    sendResponse(ctx, 200, null, update);
                }
            } catch (Exception e) {
                sendResponse(ctx, 500, "Failed to read latest update", null);
            }
        }
    }

    private static void handleGetUpdateRequest(Context ctx) {
        var version = ctx.pathParam("version");
        if (version.isEmpty()) {
            sendResponse(ctx, 400, "Version not specified", null);
            return;
        }

        var update = fetchUpdate(version);
        if (update != null) {
            sendResponse(ctx, 200, null, update);
        } else {
            sendResponse(ctx, 404, "Update not found", null);
        }
    }

    private static void handleGetFullUpdateRequest(Context ctx) {
        var version = ctx.pathParam("version");
        if (version.isEmpty()) {
            sendResponse(ctx, 400, "Version not specified", null);
            return;
        }

        var versions = getVersions();
        int latestIndex = versions.size() - 1;
        Update currentCtxVersion = fetchUpdate(version);
        if (currentCtxVersion == null) {
            sendResponse(ctx, 404, "Update not found", null);
            return;
        }

        int startingIndex = versions.indexOf(currentCtxVersion.getMeta()) + 1; // start from the next version from the current version

        if (startingIndex > latestIndex) {
            sendResponse(ctx, 400, "Invalid version specified", null);
            return;
        }

        var mergedUpdate = mergeUpdates(startingIndex, latestIndex);
        sendResponse(ctx, 200, null, mergedUpdate);
    }

    private static void handleCreateUpdateRequest(Context ctx) {
        var key = ctx.header("X-API-Key");
        if (key == null || !key.equals(apiKey)) {
            sendResponse(ctx, 403, "Invalid API key", null);
            return;
        }

        var update = ctx.bodyValidator(Update.class).get();
        var file = getUpdateFile(update.getMeta().getVersion());
        if (file.exists()) {
            sendResponse(ctx, 400, "Update already exists", null);
            return;
        }

        try (var writer = new BufferedWriter(new FileWriter(file))) {
            gson.toJson(update, writer);
            sendResponse(ctx, 200, "Update created", update);

            var meta = update.getMeta();
            if (latest == null || meta.compareTo(latest) > 0) {
                latest = meta;
                var latestFile = updatesDir.resolve("latest.json");
                try (var latestWriter = new BufferedWriter(new FileWriter(latestFile.toFile()))) {
                    gson.toJson(latest, latestWriter);
                }
            }

            updateCache.put(meta.getVersion(), update);
            addToVersions(meta);
        } catch (Exception e) {
            logger.error("Failed to create update", e);
            sendResponse(ctx, 500, "Failed to create update", null);
        }
    }

    public static Update fetchUpdate(String version) {
        if (updateCache.containsKey(version)) {
            return updateCache.get(version);
        }

        var updateFile = getUpdateFile(version);
        if (!updateFile.exists()) {
            return null;
        }

        try (var reader = new BufferedReader(new FileReader(updateFile))) {
            var update = gson.fromJson(reader, Update.class);
            if (update == null) {
                return null;
            } else {
                updateCache.put(version, update);
                return update;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static File getUpdateFile(String version) {
        var versionPieces = version.split("\\.");
        var updatePath = updatesDir.resolve(versionPieces[0]);
        for (int i = 1; i < versionPieces.length; i++)
            updatePath = updatePath.resolve(versionPieces[i]);

        if (!updatePath.toFile().exists()) {
            if (!updatePath.toFile().mkdirs()) {
                logger.error("Failed to create directory: " + updatePath.toAbsolutePath());
            }
        }

        return updatePath.resolve(version + ".json").toFile();
    }


    private static <T extends Serializable> void sendResponse(Context ctx, int code, String message, T data) {
        ctx.status(code);
        ctx.json(new Response<>(code, message, data));
    }

    private static void addToVersions(UpdateMeta meta) {
        var versionList = getVersions();
        versionList.add(meta);
        versions = versionList.stream().sorted().collect(Collectors.toList());

        try (var writer = new BufferedWriter(new FileWriter(updatesDir.resolve("versions.json").toFile()))) {
            gson.toJson(versions, writer);
        } catch (IOException e) {
            logger.error("Failed to write versions file", e);
        }
    }

    public static Update getLatestUpdate() {
        if (latest == null) {
            var latestFile = updatesDir.resolve("latest.json");
            if (!latestFile.toFile().exists()) {
                logger.error("Latest update not found");
                return null;
            }

            try (var reader = new BufferedReader(new FileReader(latestFile.toFile()))) {
                latest = gson.fromJson(reader, UpdateMeta.class);
            } catch (Exception e) {
                logger.error("Failed to read latest update", e);
                return null;
            }
        }
        return fetchUpdate(latest.getVersion());
    }

    public static UpdateMeta getLatest() {
        if (latest == null) {
            getLatestUpdate();
        }
        return latest;
    }

    public static List<UpdateMeta> getVersions() {
        if (versions == null) {
            var versionsFile = updatesDir.resolve("versions.json");
            if (!versionsFile.toFile().exists()) {
                try (var writer = new BufferedWriter(new FileWriter(versionsFile.toFile()))) {
                    gson.toJson(List.of(), writer);
                } catch (IOException e) {
                    logger.error("Failed to create versions file", e);
                }

                versions = new ArrayList<>();
            } else {
                try (var reader = new BufferedReader(new FileReader(updatesDir.resolve("versions.json").toFile()))) {
                    versions = gson.fromJson(reader, new TypeToken<List<UpdateMeta>>() {
                    }.getType());
                } catch (Exception e) {
                    logger.error("Failed to read versions file", e);
                    versions = new ArrayList<>();
                }
            }
        }
        return versions;
    }

    private static Update mergeUpdates(int startingIndex, int latestIndex) {
        if (startingIndex == latestIndex) {
            return fetchUpdate(versions.get(startingIndex).getVersion());
        }

        var updateVersions = getVersions().subList(startingIndex, latestIndex + 1);
        var changes = new HashMap<String, UpdateEntry>();

        for (var updateVersion : updateVersions) {
            var update = fetchUpdate(updateVersion.getVersion());
            if (update == null) { // Edge case: update not found
                logger.warn("Failed to find update while merging: " + updateVersion.getVersion());
                continue;
            }

            for (var entry : update.getEntries()) {
                changes.put(entry.getId(), entry);
            }
        }

        return Update.builder()
                .meta(getLatest())
                .entries(changes.values().toArray(new UpdateEntry[0]))
                .build();
    }
}

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
    private static List<String> channels;

    private static final Map<String, Map<String, Update>> updateCache = new HashMap<>();
    private static final Map<String, UpdateMeta> latest = new HashMap<>();
    private static final Map<String, List<UpdateMeta>> versions = new HashMap<>();

    public static void main(String[] args) throws InterruptedException {
        logger.info("Starting Update API...");

        updatesDir = Path.of("./").resolve("updates");
        port = 8080;
        channels = new ArrayList<>(List.of("dev", "beta", "stable"));

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
            } else if (arg.equalsIgnoreCase("--channels")) {
                if (i + 1 >= args.length) {
                    logger.error("Channels not specified");
                    return;
                }
                var channelsString = args[++i];
                if (channelsString.startsWith("\"")) {
                    while (!channelsString.endsWith("\"")) {
                        channelsString += " " + args[++i];
                    }
                }
                channels = new ArrayList<>(List.of(channelsString.replace("\"", "").split(",")));
                logger.info("Using channels: {}", channels);
            } else {
                logger.error("Unknown argument: {}", arg);
            }
        }

        if (!updatesDir.toFile().exists() && !updatesDir.toFile().mkdirs()) {
            logger.warn("Failed to create directory: {}", updatesDir.toAbsolutePath());
        }

        for (String channel : channels) {
            if (!updatesDir.resolve(channel).toFile().exists()) {
                if (!updatesDir.resolve(channel).toFile().mkdirs()) {
                    logger.warn("Failed to create directory: {}", updatesDir.resolve(channel).toAbsolutePath());
                }
            }

            updateCache.put(channel, new HashMap<>());
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
        webApp.get("/{channel}/latest", UpdateAPI::handleLatestUpdateRequest);
        webApp.get("/{channel}/get/{version}", UpdateAPI::handleGetUpdateRequest);
        webApp.get("/{channel}/getFull/{version}", UpdateAPI::handleGetFullUpdateRequest);
        webApp.post("/{channel}/create", UpdateAPI::handleCreateUpdateRequest);

    }

    private static void handleLatestUpdateRequest(Context ctx) {
        var channel = ctx.pathParam("channel");
        if (!channels.contains(channel)) {
            sendResponse(ctx, 400, "Invalid channel specified", null);
            return;
        }

        var latestUpdate = latest.get(channel);
        if (latestUpdate != null) {
            sendResponse(ctx, 200, null, latestUpdate);
            return;
        }

        var channelDir = updatesDir.resolve(channel);

        var latestUpdateDir = channelDir.resolve("latest.json");
        if (!latestUpdateDir.toFile().exists()) {
            sendResponse(ctx, 404, "Latest update not found", null);
        } else {
            try (var reader = new BufferedReader(new FileReader(latestUpdateDir.toFile()))) {
                var update = gson.fromJson(reader, UpdateMeta.class);
                if (update == null) {
                    sendResponse(ctx, 404, "Latest update not found", null);
                } else {
                    latest.put(channel, update);
                    sendResponse(ctx, 200, null, update);
                }
            } catch (Exception e) {
                sendResponse(ctx, 500, "Failed to read latest update", null);
            }
        }
    }

    private static void handleGetUpdateRequest(Context ctx) {
        var channel = ctx.pathParam("channel");
        if (!channels.contains(channel)) {
            sendResponse(ctx, 400, "Invalid channel specified", null);
            return;
        }

        var version = ctx.pathParam("version");
        if (version.isEmpty()) {
            sendResponse(ctx, 400, "Version not specified", null);
            return;
        }

        var update = fetchUpdate(channel, version);
        if (update != null) {
            sendResponse(ctx, 200, null, update);
        } else {
            sendResponse(ctx, 404, "Update not found", null);
        }
    }

    private static void handleGetFullUpdateRequest(Context ctx) {
        var channel = ctx.pathParam("channel");
        if (!channels.contains(channel)) {
            sendResponse(ctx, 400, "Invalid channel specified", null);
            return;
        }

        var version = ctx.pathParam("version");
        if (version.isEmpty()) {
            sendResponse(ctx, 400, "Version not specified", null);
            return;
        }

        var versions = getVersions(channel);
        int latestIndex = versions.size() - 1;
        Update currentCtxVersion = fetchUpdate(channel, version);
        if (currentCtxVersion == null) {
            sendResponse(ctx, 404, "Update not found", null);
            return;
        }

        int startingIndex = versions.indexOf(currentCtxVersion.getMeta()) + 1; // start from the next version from the current version

        if (startingIndex > latestIndex) {
            sendResponse(ctx, 400, "Invalid version specified", null);
            return;
        }

        var mergedUpdate = mergeUpdates(channel, startingIndex, latestIndex);
        sendResponse(ctx, 200, null, mergedUpdate);
    }

    private static void handleCreateUpdateRequest(Context ctx) {
        var channel = ctx.pathParam("channel");
        if (!channels.contains(channel)) {
            sendResponse(ctx, 400, "Invalid channel specified", null);
            return;
        }

        var key = ctx.header("X-API-Key");
        if (key == null || !key.equals(apiKey)) {
            sendResponse(ctx, 403, "Invalid API key", null);
            return;
        }

        var update = ctx.bodyValidator(Update.class).get();
        var file = getUpdateFile(channel, update.getMeta().getVersion());
        if (file.exists()) {
            sendResponse(ctx, 400, "Update already exists", null);
            return;
        }

        try (var writer = new BufferedWriter(new FileWriter(file))) {
            gson.toJson(update, writer);
            sendResponse(ctx, 200, "Update created", update);

            var meta = update.getMeta();
            var latestUpdate = latest.get(channel);
            if (latestUpdate == null || meta.compareTo(latestUpdate) > 0) {
                latest.put(channel, update.getMeta());
                var latestFile = updatesDir.resolve(channel).resolve("latest.json");
                try (var latestWriter = new BufferedWriter(new FileWriter(latestFile.toFile()))) {
                    gson.toJson(latest.get(channel), latestWriter);
                }
            }

            updateCache.get(channel).put(meta.getVersion(), update);
            addToVersions(channel, meta);
        } catch (Exception e) {
            logger.error("Failed to create update", e);
            sendResponse(ctx, 500, "Failed to create update", null);
        }
    }

    public static Update fetchUpdate(String channel, String version) {
        if (updateCache.containsKey(channel)) {
            return updateCache.get(channel).getOrDefault(version, null);
        }

        var updateFile = getUpdateFile(channel, version);
        if (!updateFile.exists()) {
            return null;
        }

        try (var reader = new BufferedReader(new FileReader(updateFile))) {
            var update = gson.fromJson(reader, Update.class);
            if (update == null) {
                return null;
            } else {
                updateCache.get(channel).put(version, update);
                return update;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static File getUpdateFile(String channel, String version) {
        var versionPieces = version.split("\\.");
        var updatePath = updatesDir.resolve(channel).resolve(versionPieces[0]);
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

    private static void addToVersions(String channel, UpdateMeta meta) {
        var versionList = getVersions(channel);
        versionList.add(meta);
        versions.put(channel, versionList.stream().sorted().collect(Collectors.toList()));

        try (var writer = new BufferedWriter(new FileWriter(updatesDir.resolve(channel).resolve("versions.json").toFile()))) {
            gson.toJson(versions.get(channel), writer);
        } catch (IOException e) {
            logger.error("Failed to write versions file", e);
        }
    }

    public static Update getLatestUpdate(String channel) {
        if (latest.get(channel) == null) {
            var latestFile = updatesDir.resolve(channel).resolve("latest.json");
            if (!latestFile.toFile().exists()) {
                logger.error("Latest update not found");
                return null;
            }

            try (var reader = new BufferedReader(new FileReader(latestFile.toFile()))) {
                latest.put(channel, gson.fromJson(reader, UpdateMeta.class));
            } catch (Exception e) {
                logger.error("Failed to read latest update", e);
                return null;
            }
        }
        return fetchUpdate(channel, latest.get(channel).getVersion());
    }

    public static UpdateMeta getLatest(String channel) {
        if (latest.get(channel) == null) {
            getLatestUpdate(channel);
        }
        return latest.get(channel);
    }

    public static List<UpdateMeta> getVersions(String channel) {
        if (versions.get(channel) == null) {
            var versionsFile = updatesDir.resolve(channel).resolve("versions.json");
            if (!versionsFile.toFile().exists()) {
                try (var writer = new BufferedWriter(new FileWriter(versionsFile.toFile()))) {
                    gson.toJson(List.of(), writer);
                } catch (IOException e) {
                    logger.error("Failed to create versions file", e);
                }

                versions.put(channel, new ArrayList<>());
            } else {
                try (var reader = new BufferedReader(new FileReader(versionsFile.toFile()))) {
                    versions.put(channel, gson.fromJson(reader, new TypeToken<List<UpdateMeta>>() {
                    }.getType()));
                } catch (Exception e) {
                    logger.error("Failed to read versions file", e);
                    versions.put(channel, new ArrayList<>());
                }
            }
        }
        return versions.get(channel);
    }

    private static Update mergeUpdates(String channel, int startingIndex, int latestIndex) {
        if (startingIndex == latestIndex) {
            return fetchUpdate(channel, versions.get(channel).get(startingIndex).getVersion());
        }

        var updateVersions = getVersions(channel).subList(startingIndex, latestIndex + 1);
        var changes = new HashMap<String, UpdateEntry>();

        for (var updateVersion : updateVersions) {
            var update = fetchUpdate(channel, updateVersion.getVersion());
            if (update == null) { // Edge case: update not found
                logger.warn("Failed to find update while merging: {}", updateVersion.getVersion());
                continue;
            }

            for (var entry : update.getEntries()) {
                changes.put(entry.getId(), entry);
            }
        }

        return Update.builder()
                .meta(getLatest(channel))
                .entries(changes.values().toArray(new UpdateEntry[0]))
                .build();
    }
}

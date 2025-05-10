package dev.armenderoian.update.updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.armenderoian.modpack.update.models.Response;
import net.armenderoian.modpack.update.models.Update;
import net.armenderoian.modpack.update.models.UpdateEntry;
import net.armenderoian.modpack.update.models.UpdateMeta;
import net.armenderoian.modpack.update.util.Config;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class Updater {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger logger = LoggerFactory.getLogger(Updater.class);

    public static void main(String[] args) {
        logger.info("Starting ModPack Updater...");

        if (args.length == 0) {
            updateModpack();
            return;
        }

        var mode = args[0];
        switch (mode) {
            case "update" -> updateModpack();
            case "dump" -> dumpModpack();
            case "create" -> {
                if (args.length >= 2) {
                    createUpdate(args[1].equals("push"));
                } else {
                    createUpdate(false);
                }
            }
            default -> logger.error("Unknown mode: {}", mode);
        }
    }

    public static void updateModpack() {
        // Create a lock file.
        // Check for existing lock file
        long pid = ProcessHandle.current().pid();
        Path lockFile = Paths.get("update.lock");
        if (Files.exists(lockFile)) {
            try {
                String pidString = Files.readString(lockFile);
                long existingPid = Long.parseLong(pidString);
                if (existingPid != pid) {
                    logger.error("Another instance of the updater is already running (PID: {})", existingPid);
                    return;
                }
            } catch (IOException e) {
                logger.error("Failed to read update.lock", e);
                return;
            }
        } else {
            // Create the lock file
            try {
                Files.writeString(lockFile, Long.toString(pid), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                logger.error("Failed to write update.lock", e);
                return;
            }
        }

        // Load the config
        var configPath = Path.of("./config");
        Config config = Config.load(configPath);
        if (configPath.toFile().exists()) Config.save(configPath, config);
        logger.info("Loaded config: {}", configPath.toAbsolutePath());

        // Read the current version
        UpdateMeta currentVersion = config.getCurrentVersion();
        logger.info("Current version: v{}", currentVersion.getVersion());

        String channel = config.getChannel();
        UpdateMeta latest = null;
        Update update;

        var tmpDir = Path.of("./tmp/updater/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
        var updatePath = Path.of("./mods");
        var toRemove = new ArrayList<Path>();
        var toMove = new ArrayList<Pair<Path, Path>>();


        // Start the update
        boolean success = true;
        try (var client = HttpClient.newHttpClient()) {
            // Fetch the latest version
            var response = client.send(HttpRequest.newBuilder().GET().uri(URI.create(config.getUpdateUrl() + "/" + channel + "/latest")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new RuntimeException("Failed to fetch latest version (code: " + response.statusCode() + ")");

            latest = GSON.fromJson(response.body(), new TypeToken<Response<UpdateMeta>>() {}).getData();
            if (latest == null)
                throw new RuntimeException("Failed to fetch latest version, not found in response.");

            logger.info("Latest version: v{}", latest.getVersion());

            // Check if we need to update
            if (latest.getVersion().equals(currentVersion.getVersion())) {
                logger.info("You are already on the latest version.");
                return;
            } else {
                logger.info("A new version is available! Version: v{} - {}", latest.getVersion(), latest.getDescription());
            }

            // Fetch the latest update
            // API merges and missing updates into the latast update
            logger.info("Fetching update manifest...");
            var manifest = client.send(HttpRequest.newBuilder().GET().uri(URI.create(config.getUpdateUrl() + "/" + channel + "/getFull/" + currentVersion.getVersion())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (manifest.statusCode() != 200)
                throw new RuntimeException("Failed to fetch update manifest (code: " + manifest.statusCode() + ")");

            update = GSON.fromJson(manifest.body(), new TypeToken<Response<Update>>() {}).getData();
            if (update == null)
                throw new RuntimeException("Failed to fetch update manifest, not found in response.");

            logger.info("Update manifest: {}", GSON.toJson(update));
            // Begin the update
            logger.info("Starting update to: v{}", latest.getVersion());

            // Create the directories for the update
            tmpDir.toFile().mkdirs();
            updatePath.toFile().mkdirs();

            int curr = 1;
            int total = update.getEntries().length;
            var mods = listMods(updatePath);

            for (var entry : update.getEntries()) {
                logger.info("Updating mod [{}/{}]: {}", curr++, total, entry.getName());

                var oldModFilename = mods.containsKey(entry.getId()) ? mods.get(entry.getId()).getFirst() : null;

                // If the the mod was not added, and exists on disk, delete it
                if (entry.getType() != UpdateEntry.Type.ADDED && oldModFilename != null) {
                    logger.info("Deleting mod file: {}", oldModFilename);
                    var removePath = updatePath.resolve(oldModFilename);
                    if (!Files.exists(removePath))
                        throw new RuntimeException("Trying to delete a file that does not exist: " + removePath.toAbsolutePath());
                    toRemove.add(removePath);
                    continue;
                }

                var updatedModFilename = entry.getFormattedFileName();

                // If the mod was added, fetch it from the download URL
                logger.info("Downloading: {} -> {}", entry.getDownloadUrl(), updatedModFilename);
                var file = tmpDir.resolve(updatedModFilename);
                var downloadResponse = client.send(HttpRequest.newBuilder().GET().uri(URI.create(encodePath(entry.getDownloadUrl()))).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (downloadResponse.statusCode() != 200) {
                    throw new RuntimeException("Failed to download mod file: " + downloadResponse.statusCode());
                }

                try (var in = downloadResponse.body()) {
                    try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }

                    toMove.add(Pair.of(file, updatePath.resolve(updatedModFilename)));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to download mod file: " + downloadResponse.statusCode(), e);
                }

                logger.info("Downloaded: {} -> {}", entry.getDownloadUrl(), file);
            }


        } catch (Exception e) {
            logger.error("There was an error during the update process, aborting...", e);
            success = false;
        }

        // If the update was successful, move the files to the mods folder
        if (success) {
            // Move the files to the mods folder
            logger.info("Moving files to mods folder...");
            for (var entry : toMove) {
                var file = entry.getFirst();
                var target = entry.getSecond();
                try {
                    Files.move(file, target);
                    logger.info("Moved: {} -> {}", file, target);
                } catch (IOException e) {
                    logger.error("Failed to move file: {} -> {}", file, target, e);
                }
            }

            // Delete the removed mods
            logger.info("Deleting removed mods...");
            for (var entry : toRemove) {
                try {
                    Files.delete(entry);
                    logger.info("Deleted: {}", entry);
                } catch (IOException e) {
                    logger.error("Failed to delete file: {}", entry, e);
                }
            }

            // Delete the temporary directory
            try (var stream = Files.walk(tmpDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                logger.error("Failed to delete file: {}", path, e);
                            }
                        });
            } catch (IOException e) {
                logger.error("Failed to delete temporary directory: {}", tmpDir, e);
            }

            config.setCurrentVersion(latest);
            Config.save(configPath, config);

            logger.info("Update complete!");
        }

        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            logger.error("Failed to delete update.lock", e);
        }
    }

    public static void dumpModpack() {
        var path = Path.of("./mods");
        var mods = listMods(path);

        StringBuilder modList = new StringBuilder();
        AtomicInteger i = new AtomicInteger();
        int total = mods.size();
        mods.forEach((mod, info) -> {
            var modId = info.getFirst();
            var modJson = info.getSecond();
            var modName = modJson.get("name").getAsString();
            var modVersion = modJson.get("version").getAsString();
            var modDescription = modJson.get("description").getAsString();

            String downloadUrl = getDownloadUrl(path, modId, info.getFirst(), null);

            modList.append(" -> ")
                    .append(mod)
                    .append(" | ")
                    .append(modName)
                    .append(" v")
                    .append(modVersion)
                    .append(" - ")
                    .append(modDescription)
                    .append("\n")
                    .append("    Download URL: ")
                    .append(downloadUrl != null ? downloadUrl : "NOT FOUND")
                    .append("\n");

            logger.info("Read mod [{}/{}]: {}", i.getAndIncrement(), total, modName);
        });
        logger.info("Dumping modpack...\n{}", modList);
    }

    public static void createUpdate(boolean push) {
        var scanner = new Scanner(System.in);
        System.out.print("Enter the version: ");
        String version = scanner.nextLine();
        System.out.print("Enter the name: ");
        String name = scanner.nextLine();
        System.out.print("Enter the description: ");
        String description = scanner.nextLine();
        System.out.print("Enter the channel: ");
        String channel = scanner.nextLine();
        System.out.print("Enter the update URL: ");
        String updateUrl = scanner.nextLine();
        System.out.print("Enter the backup URL: ");
        String backupUrl = scanner.nextLine();
        System.out.print("Enter the API key: ");
        String apiKey = scanner.nextLine();
        String date = DateFormat.getDateTimeInstance().format(new Date());

        if (version.isEmpty() || name.isEmpty() || description.isEmpty() || channel.isEmpty() || updateUrl.isEmpty()) {
            logger.error("Missing required fields");
            return;
        }
        if (backupUrl.isEmpty()) {
            logger.warn("No backup URL provided, any mod not found on Modrinth will not be downloadable.");
        }
        if (apiKey.isEmpty()) {
            logger.warn("No API key provided, the update manifest will not be uploaded.");
        }
        logger.info("Creating update manifest for version: v{}", version);

        var meta = UpdateMeta.builder()
                .version(version)
                .name(name)
                .description(description)
                .releaseDate(date)
                .build();

        var path = Path.of("./mods");
        var currentMods = listMods(path);

        Update latest = null;
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder().GET().uri(URI.create(updateUrl + "/" + channel + "/latest?update=true")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Failed to fetch latest version");
            } else {
                var responseData = GSON.fromJson(response.body(), new TypeToken<Response<Update>>() {});
                if (responseData.getCode() != 200 || responseData.getData() == null) {
                    logger.error("Failed to fetch latest version: {}", responseData.getMessage());
                } else {
                    latest = responseData.getData();
                    logger.info("Latest version: v{}", latest.getMeta().getVersion());
                }
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to fetch latest update", e);
        }

        var latestMods = latest != null ? Arrays.stream(latest.getEntries()).collect(Collectors.toMap(UpdateEntry::getId, entry -> entry)) : new HashMap<String, UpdateEntry>();
        var finalizedMods = new ArrayList<UpdateEntry>();

        // Iterate over current mods and compare with latest mods
        for (var entry : currentMods.entrySet()) {
            var modId = entry.getKey();
            var modInfo = entry.getValue();

            var modJson = modInfo.getSecond();
            var modName = modJson.get("name").getAsString();
            var modVersion = modJson.get("version").getAsString();

            var modFileName = modInfo.getFirst();
            var downloadUrl = getDownloadUrl(path, modId, modFileName, backupUrl);

            // Check if the mod is in the latest mods
            // If so, if different version then mark as CHANGED. If REMOVED in latest but present in current, mark as ADDED
            if (latestMods.containsKey(modId)) {
                var latestMod = latestMods.get(modId);
                if (!modVersion.equals(latestMod.getVersion()) || latestMod.getType() == UpdateEntry.Type.REMOVED) {
                    var status = latestMod.getType() == UpdateEntry.Type.REMOVED ? UpdateEntry.Type.ADDED :UpdateEntry.Type.CHANGED;
                    finalizedMods.add(UpdateEntry.builder()
                            .type(status)
                            .id(modId)
                            .name(modName)
                            .version(modVersion)
                            .downloadUrl(downloadUrl != null ? downloadUrl.replace(".disabled", "") : null)
                            .fileName(modFileName)
                            .build());

                    if (status == UpdateEntry.Type.CHANGED) {
                        logger.info("Mod changed: {} (v{} -> v{})", modName, modVersion, latestMod.getVersion());
                    } else {
                        logger.info("Mod removed: {} (v{})", modName, modVersion);
                    }
                }
            } else {
                // If the mod was not in the latest mods, it has been added

                finalizedMods.add(UpdateEntry.builder()
                        .type(UpdateEntry.Type.ADDED)
                        .id(modId)
                        .name(modName)
                        .version(modVersion)
                        .downloadUrl(downloadUrl)
                        .fileName(modFileName)
                        .build());

                logger.info("Mod added: {} (v{})", modName, modVersion);
            }
        }

        // Iterate over latest mods and check if any were removed
        for (var entry : latestMods.entrySet()) {
            var modId = entry.getKey();
            var modInfo = entry.getValue();

            if (!currentMods.containsKey(modId) && modInfo.getType() != UpdateEntry.Type.REMOVED) {
                finalizedMods.add(UpdateEntry.builder()
                        .type(UpdateEntry.Type.REMOVED)
                        .id(modId)
                        .name(modInfo.getName())
                        .version(modInfo.getVersion())
                        .downloadUrl(modInfo.getDownloadUrl())
                        .fileName(modInfo.getFileName())
                        .build());

                logger.info("Mod removed: {} (v{})", modInfo.getName(), modInfo.getVersion());
            }
        }

        var update = Update.builder()
                .meta(meta)
                .entries(finalizedMods.toArray(new UpdateEntry[0]))
                .build();

        logger.info("Update manifest: \n{}", GSON.toJson(update));

        if (push && !apiKey.isEmpty()) {
            if (latest != null && update.compareTo(latest) <= 0) {
                logger.warn("Skipping upload, version {} is not greater than the latest version {}", version, latest.getMeta().getVersion());
                return;
            }

            if (finalizedMods.isEmpty()) {
                logger.info("Skipping upload, no changes found.");
                return;
            }

            logger.info("Uploading update manifest...");
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder()
                                .header("X-API-Key", apiKey)
                                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(update)))
                                .uri(URI.create(updateUrl + "/" + channel + "/create"))
                                .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    logger.error("Failed to upload update manifest (Code: {}): {}", response.statusCode(), response.body());
                }
            } catch (IOException | InterruptedException e) {
                logger.error("Failed to upload update manifest", e);
            }
        }
    }

    public static Map<String, Pair<String, JsonObject>> listMods(Path path) {
        try (var listStream = Files.list(path)) {
            return listStream.filter(Files::isRegularFile)
                    .map(listPath -> listPath.getFileName().toString())
                    .map(file -> {
                        try (JarFile jar = new JarFile(path.resolve(file).toFile())) {
                            var entry = jar.getEntry("fabric.mod.json");
                            if (entry == null) {
                                logger.warn("Found a non-Fabric mod: {}", file);
                                return null;
                            }

                            try (var stream = jar.getInputStream(entry);
                                 var reader = new InputStreamReader(stream)) {
                                return Pair.of(file, new Gson().fromJson(reader, JsonObject.class));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(
                            json -> json.getSecond().get("id").getAsString(),
                            json -> json,
                            (existing, replacement) -> existing // Handle duplicate keys
                    ));
        } catch (IOException e) {
            logger.error("Failed to list mods", e);
        }

        return Map.of();
    }

    @Getter
    @AllArgsConstructor
    public static class Pair<K, V> {
        private final K first;
        private final V second;

        public static <K, V> Pair<K, V> of(K first, V second) {
            return new Pair<>(first, second);
        }
    }

    public static String hashFile(Path path, String alg) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(alg);
        try (InputStream is = new FileInputStream(path.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    public static @Nullable String getDownloadUrl(Path modFolder, String modId, String filename, @Nullable String backupUrl) {
        String downloadUrl = null;
        try (var client = HttpClient.newHttpClient()) {
            var fileHash = hashFile(modFolder.resolve(filename), "SHA-1"); // Modrinth uses SHA-1 for file hashes
            var response = client.send(HttpRequest.newBuilder().GET().uri(URI.create("https://api.modrinth.com/v2/version_file/" + fileHash)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Failed to find mod '{}' on Modrinth: (Code: {}). Using backup URL", modId, response.statusCode());
            } else {
                var body = GSON.fromJson(response.body(), JsonObject.class);
                downloadUrl = "https://cdn.modrinth.com/data/" + body.get("project_id").getAsString() + "/versions/" + body.get("id").getAsString() + "/" + filename.replace(".disabled", "");
            }
        } catch (Exception e) {
            logger.error("Failed to find update url for mod: {}", filename, e);
        }

        return downloadUrl != null ? downloadUrl : (backupUrl != null ? backupUrl + "/" + filename : null);
    }

    private static String encodePath(String path) {
        // Replace illegal characters manually (common ones like spaces, brackets, etc.)
        return path
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace(" ", "%20");
    }
}

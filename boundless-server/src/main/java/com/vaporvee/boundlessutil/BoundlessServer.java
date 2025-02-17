package com.vaporvee.boundlessutil;

import com.google.gson.JsonArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BoundlessServer {
    private static final Logger logger = LogManager.getLogger(BoundlessServer.class);
    private static final String currentDir = System.getProperty("user.dir");
    private static final String installerFileName = "installer.jar";
    private static final String serverConfigFileName = "boundless-server.json";
    private static boolean nogui = false;

    public static void main(String[] args) {
        writeJvmArgsToFile(args);
        if (isServerUpdated()) {
            logger.info("Server is already updated. Skipping download and installation steps.");
            launchServer();
            return;
        }

        String neoForgeVersion = "21.1.119"; // need to auto update in the future
        String modpack = "boundless";
        String jarUrl = String.format("https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-installer.jar", neoForgeVersion, neoForgeVersion);

        logger.info("Downloading and installing NeoForge Server...");
        downloadAndExecuteInstaller(jarUrl);

        deleteFileIfExists(Path.of(currentDir, installerFileName));

        String[] excludedPaths = {
                "mods/Sounds",
                "mods/sodium-extra",
                "mods/watermedia",
                "mods/world-host",
                "mods/betterpingdisplay-fabric"
        };
        downloadMrPack(getLatestModpackUrl(modpack, "release"), excludedPaths);

        markServerAsUpdated();
        launchServer();
    }

    public static String getLatestModpackUrl(String modpackSlug, String channel) {
        String apiUrl = "https://api.modrinth.com/v2/project/" + modpackSlug + "/version";

        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "application/json");

            InputStream inputStream = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            reader.close();

            JsonArray versions = JsonParser.parseString(response.toString()).getAsJsonArray();
            for (var versionElement : versions) {
                JsonObject version = versionElement.getAsJsonObject();
                if (version.get("version_type").getAsString().equals(channel)) {
                    JsonArray files = version.getAsJsonArray("files");
                    return files.get(0).getAsJsonObject().get("url").getAsString();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private static void writeJvmArgsToFile(String[] args) {
        Path jvmArgsFile = Path.of(currentDir, "user_jvm_args.txt");
        logger.warn(args);
        try (BufferedWriter writer = Files.newBufferedWriter(jvmArgsFile)) {
            for (String arg : args) {
                if (arg.equalsIgnoreCase("nogui") || arg.equalsIgnoreCase("--nogui")) {
                    writeJvmArgsFromRuntime();
                    nogui = true;
                    break;
                }
                writer.write(arg);
                writer.newLine();
            }
            logger.info("JVM arguments written to: {}", jvmArgsFile);
        } catch (IOException e) {
            logger.error("Error writing to user_jvm_args.txt: {}", e.getMessage());
        }
    }

    private static void writeJvmArgsFromRuntime() {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> jvmArgs = runtimeMxBean.getInputArguments();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter("user_jvm_args.txt", true))) {
            for (String arg : jvmArgs) {
                writer.write(arg);
                writer.newLine();
            }
            System.out.println("JVM arguments written to user_jvm_args.txt");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static boolean isServerUpdated() {
        Path configFilePath = Path.of(currentDir, serverConfigFileName);
        if (Files.exists(configFilePath)) {
            try (Reader reader = Files.newBufferedReader(configFilePath)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                return jsonObject.has("updated") && jsonObject.get("updated").getAsBoolean();
            } catch (IOException e) {
                logger.error("Error reading {}: {}", serverConfigFileName, e.getMessage());
            }
        }
        return false;
    }

    private static void markServerAsUpdated() {
        JsonObject config = new JsonObject();
        config.addProperty("updated", true);

        Path configFilePath = Path.of(currentDir, serverConfigFileName);
        try (Writer writer = Files.newBufferedWriter(configFilePath)) {
            writer.write(config.toString());
            logger.info("Server update marked as complete in {}", serverConfigFileName);
        } catch (IOException e) {
            logger.error("Error writing to {}: {}", serverConfigFileName, e.getMessage());
        }
    }

    private static boolean shouldProcessFile(JsonObject fileObject, String[] excludedPaths) {
        String path = fileObject.get("path").getAsString();
        if (path.contains("mods/") && "required".equals(fileObject.getAsJsonObject("env").get("server").getAsString())) {
            for (String exclude : excludedPaths) {
                if (path.startsWith(exclude)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static void downloadAndExecuteInstaller(String fileURL) {
        String destinationPath = currentDir + File.separator + installerFileName;

        try {
            logger.info("Downloading file from: {}", fileURL);
            URL url = new URI(fileURL).toURL();

            try (InputStream inputStream = url.openStream()) {
                Files.copy(inputStream, Paths.get(destinationPath), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Download completed: {}", destinationPath);
            }

            Path downloadedFile = Paths.get(destinationPath);
            if (Files.exists(downloadedFile)) {
                long fileSize = Files.size(downloadedFile);
                logger.info("Found installer.jar with size: {} bytes, proceeding to execute...", fileSize);

                executeInstaller(new String[]{"-jar", destinationPath, "--install-server"});
            } else {
                logger.error("installer.jar not found at expected path: {}", destinationPath);
                System.exit(1);
            }
        } catch (IOException | URISyntaxException e) {
            logger.error("Error downloading file from {}: {}", fileURL, e.getMessage());
            System.exit(1);
        }
    }

    private static void executeInstaller(String[] jarArgs) {
        Process installerProcess = executeJarFile(jarArgs, false);
        if (installerProcess == null) {
            logger.error("Failed to start the installer.jar process. Exiting program.");
            System.exit(1);
        }

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(installerProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                logger.error("Error reading installer output: {}", e.getMessage());
            }
        }).start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(installerProcess.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (IOException e) {
                logger.error("Error reading installer error output: {}", e.getMessage());
            }
        }).start();

        try {
            installerProcess.waitFor();
        } catch (InterruptedException e) {
            logger.warn("Installer process interrupted: {}", e.getMessage());
        }
    }

    private static void launchServer() {
        String neoForgeVersion = "21.1.62";
        String osSpecificArgs = System.getProperty("os.name").startsWith("Windows")
                ? "@libraries/net/neoforged/neoforge/" + neoForgeVersion + "/win_args.txt"
                : "@libraries/net/neoforged/neoforge/" + neoForgeVersion + "/unix_args.txt";

        logger.info("Starting Boundless Horizons Server...");

        List<String> commandArgs = new ArrayList<>();
        commandArgs.add(osSpecificArgs);

        String argSuffix = System.getProperty("os.name").startsWith("Windows") ? "%*" : "\"$@\"";
        commandArgs.add(argSuffix);
        if(nogui){
            commandArgs.add("nogui");
        }

        Process serverProcess = executeJarFile(commandArgs.toArray(new String[0]), true);

        try {
            int exitCode = serverProcess.waitFor();
            logger.info("Server process exited with code: {}", exitCode);
        } catch (InterruptedException e) {
            logger.warn("Waiting for server process interrupted: {}", e.getMessage());
        }
    }

    private static Process executeJarFile(String[] javaArgs, boolean inheritIO) {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(Arrays.asList(javaArgs));

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(new File(currentDir));

            if (inheritIO) {
                processBuilder.redirectErrorStream(true).inheritIO();
            }

            Process process = processBuilder.start();
            return process;

        } catch (IOException e) {
            logger.error("Error executing command: {}", command.toString(), e);
            return null;
        }
    }


    private static void downloadMrPack(String fileURL, String[] excludedPaths) {
        Path tempMrPackPath = Path.of(currentDir, "temp.mrpack");
        try {
            logger.info("Downloading .mrpack file from: {}", fileURL);
            URL url = new URI(fileURL).toURL();
            try (InputStream inputStream = url.openStream()) {
                Files.copy(inputStream, tempMrPackPath, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Download completed: {}", tempMrPackPath);

            unzipMrPack(tempMrPackPath.toString());

            Path modrinthIndexFile = Path.of(currentDir, "modrinth.index.json");
            if (!Files.exists(modrinthIndexFile)) {
                logger.error("Error: temp.mrpack does not contain a valid modrinth.index.json file.");
                return;
            }

            logger.info("Processing modpack...");
            try (Reader reader = Files.newBufferedReader(modrinthIndexFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray files = jsonObject.getAsJsonArray("files");

                for (var fileElement : files) {
                    JsonObject fileObject = fileElement.getAsJsonObject();
                    String path = fileObject.get("path").getAsString();
                    String downloadUrl = fileObject.getAsJsonArray("downloads").get(0).getAsString();
                    String expectedSha1 = fileObject.getAsJsonObject("hashes").get("sha1").getAsString();
                    String expectedSha512 = fileObject.getAsJsonObject("hashes").get("sha512").getAsString();

                    if (shouldProcessFile(fileObject, excludedPaths)) {
                        Path outputPath = Path.of(currentDir, path);
                        Files.createDirectories(outputPath.getParent());
                        logger.info("Downloading mod: {}", path);

                        boolean downloadSuccessful = downloadWithRetry(downloadUrl, outputPath, expectedSha1, expectedSha512);
                        if (!downloadSuccessful) {
                            logger.error("Failed to download {} after retries", path);
                        }
                    } else {
                        logger.info("Skipping: {}", path);
                    }
                }
            } catch (IOException e) {
                logger.error("Error processing modrinth.index.json: {}", e.getMessage());
            }

            copyOverrides();
            Files.deleteIfExists(tempMrPackPath);
            Files.deleteIfExists(modrinthIndexFile);

        } catch (IOException | URISyntaxException e) {
            logger.error("Error downloading or processing .mrpack file: {}", e.getMessage());
        }
    }

    private static boolean downloadWithRetry(String downloadUrl, Path outputPath, String expectedSha1, String expectedSha512) {
        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try (InputStream in = new URL(downloadUrl).openStream()) {
                Files.copy(in, outputPath, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Downloaded {} to {}", outputPath.getFileName(), outputPath);

                if (verifyFileHashes(outputPath.toFile(), expectedSha1, expectedSha512)) {
                    return true;
                } else {
                    logger.error("File hash verification failed for: {}", outputPath.getFileName());
                }
            } catch (IOException e) {
                logger.error("Failed to download mod {}: {}", outputPath.getFileName(), e.getMessage());
            }
        }
        return false;
    }

    private static boolean verifyFileHashes(File file, String expectedSha1, String expectedSha512) {
        try {
            String computedSha1 = computeFileHash(file, "SHA-1");
            String computedSha512 = computeFileHash(file, "SHA-512");
            return computedSha1.equals(expectedSha1) && computedSha512.equals(expectedSha512);
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA algorithm not found: {}", e.getMessage());
            return false;
        } catch (IOException e) {
            logger.error("Error reading file for hash verification: {}", e.getMessage());
            return false;
        }
    }

    private static String computeFileHash(File file, String algorithm) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void copyOverrides() {
        Path modsSource = Path.of(currentDir, "overrides", "mods");
        Path configSource = Path.of(currentDir, "overrides", "config");

        if (Files.exists(modsSource)) {
            try {
                Files.walk(modsSource).forEach(source -> {
                    Path destination = Path.of(currentDir, "mods").resolve(modsSource.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                            logger.info("Copied mod: {}", destination);
                        }
                    } catch (IOException e) {
                        logger.error("Error copying mod: {}", source, e.getMessage());
                    }
                });
            } catch (IOException e) {
                logger.error("Error copying mods: {}", e.getMessage());
            }
        } else {
            logger.warn("Mods source directory does not exist: {}", modsSource);
        }

        if (Files.exists(configSource)) {
            try {
                Files.walk(configSource).forEach(source -> {
                    Path destination = Path.of(currentDir, "config").resolve(configSource.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                            logger.info("Copied config: {}", destination);
                        }
                    } catch (IOException e) {
                        logger.error("Error copying config: {}", source, e.getMessage());
                    }
                });
            } catch (IOException e) {
                logger.error("Error copying config: {}", e.getMessage());
            }
        }
    }

    private static void unzipMrPack(String zipFilePath) throws IOException {
        Path destDir = Path.of(currentDir);
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry = zipIn.getNextEntry();
            while (entry != null) {
                Path filePath = destDir.resolve(entry.getName());
                if (!entry.isDirectory()) {
                    Files.createDirectories(filePath.getParent());
                    try (OutputStream out = Files.newOutputStream(filePath)) {
                        zipIn.transferTo(out);
                    }
                } else {
                    Files.createDirectories(filePath);
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
        }
    }

    private static void deleteFileIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.error("Error deleting file: {}", path, e.getMessage());
        }
    }
}

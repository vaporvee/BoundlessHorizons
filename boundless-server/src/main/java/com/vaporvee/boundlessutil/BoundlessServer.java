package com.vaporvee.boundlessutil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BoundlessServer {
    private static final Logger logger = LogManager.getLogger(BoundlessServer.class);
    private static final String currentDir = System.getProperty("user.dir");
    private static final String installerFileName = "installer.jar";

    public static void main(String[] args) throws IOException {
        String neoForgeVersion = "21.1.62";
        String jarUrl = String.format("https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-installer.jar", neoForgeVersion, neoForgeVersion);

        logger.info("Downloading and installing NeoForge Server...");
        downloadAndExecuteJar(jarUrl, new String[]{"--install-server"});
        deleteFileIfExists(Path.of(currentDir, installerFileName));

        String mrPackUrl = "https://cdn.modrinth.com/data/ScTwzzH2/versions/IImtmTWG/Boundless%20Horizons%200.2.0.mrpack";
        String[] excludedPaths = {
                "mods/Sounds",
                "mods/sodium-extra",
                "mods/watermedia",
                "mods/world-host"
        };
        downloadMrPack(mrPackUrl, excludedPaths);

        launchServer();
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

    private static void launchServer() {
        String neoForgeVersion = "21.1.62";
        String osSpecificArgs = System.getProperty("os.name").startsWith("Windows")
                ? "@libraries/net/neoforged/neoforge/" + neoForgeVersion + "/win_args.txt"
                : "@libraries/net/neoforged/neoforge/" + neoForgeVersion + "/unix_args.txt";

        logger.info("Starting Boundless Horizons Server...");
        executeJarFile(new String[]{"@user_jvm_args.txt", osSpecificArgs});
    }

    private static void downloadAndExecuteJar(String fileURL, String[] jarArgs) {
        String destinationPath = currentDir + File.separator + installerFileName;

        try {
            logger.info("Downloading file from: {}", fileURL);
            URL url = new URI(fileURL).toURL();
            try (InputStream inputStream = url.openStream()) {
                Files.copy(inputStream, Paths.get(destinationPath), StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Download completed: {}", destinationPath);
        } catch (IOException | URISyntaxException e) {
            logger.error("Error downloading file from {}: {}", fileURL, e.getMessage());
            return;
        }

        logger.info("Executing the downloaded JAR file...");
        executeJarFile(new String[]{"-jar",installerFileName, "--install-server"});
    }

    private static void executeJarFile(String[] javaArgs) {
        String[] command = new String[1 + javaArgs.length];
        command[0] = "java";
        System.arraycopy(javaArgs, 0, command, 1, javaArgs.length);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).directory(new File(currentDir));
            Process process = processBuilder.start();

            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "OUTPUT");
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "ERROR");

            outputGobbler.start();
            errorGobbler.start();

            process.waitFor();

            process.getOutputStream().close();
            outputGobbler.join();
            errorGobbler.join();

        } catch (IOException | InterruptedException e) {
            logger.error("Error executing command: {}", Arrays.toString(command), e);
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

                    if (shouldProcessFile(fileObject, excludedPaths)) {
                        Path outputPath = Path.of(currentDir, path);
                        Files.createDirectories(outputPath.getParent());
                        logger.info("Downloading mod: {}", path);

                        try (InputStream in = new URL(downloadUrl).openStream()) {
                            Files.copy(in, outputPath, StandardCopyOption.REPLACE_EXISTING);
                            logger.info("Downloaded {} to {}", path, outputPath);
                        } catch (IOException e) {
                            logger.error("Failed to download mod {}: {}", path, e.getMessage());
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

    private static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        private final String type;

        public StreamGobbler(InputStream inputStream, String type) {
            this.inputStream = inputStream;
            this.type = type;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[{}] {}", type, line);
                }
            } catch (IOException e) {
                logger.error("Error reading output stream: {}", e.getMessage());
            }
        }
    }
}

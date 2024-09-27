package com.vaporvee.boundlessutil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BoundlessServer {
    private static final Logger logger = LogManager.getLogger(BoundlessServer.class);
    private static final String currentDir = System.getProperty("user.dir");
    public static void main(String[] args) throws IOException {
        logger.info("Downloading and installing NeoForge Server");
        String neoForgeVersion = "21.1.62";
        downloadAndExecuteJar(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neoForgeVersion + "/neoforge-" + neoForgeVersion + "-installer.jar",
                "installer.jar", new String[]{"--install-server"});
        logger.info("↑ Yeah no I'll do that for you automatically...");
        Files.deleteIfExists(Path.of(currentDir,"installer.jar"));
        downloadMrPack("https://cdn.modrinth.com/data/ScTwzzH2/versions/IImtmTWG/Boundless%20Horizons%200.2.0.mrpack");
    }

    public static void downloadAndExecuteJar(String fileURL, String fileName, String[] jarArgs) {
        String currentDir = System.getProperty("user.dir");
        String destinationPath = currentDir + File.separator + fileName;

        try {
            logger.info("Downloading the JAR file from: {}", fileURL);
            URI uri = new URI(fileURL);
            URL url = uri.toURL();
            InputStream inputStream = url.openStream();
            Path targetPath = Paths.get(destinationPath);
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            inputStream.close();
            logger.info("Download completed successfully: {}", destinationPath);
        } catch (IOException | URISyntaxException e) {
            logger.error("Error downloading the file from {}", fileURL, e);
            return;
        }

        try {
            logger.info("Executing the downloaded JAR file...");

            String[] command = new String[3 + jarArgs.length];
            command[0] = "java";
            command[1] = "-jar";
            command[2] = fileName;
            System.arraycopy(jarArgs, 0, command, 3, jarArgs.length);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(new File(currentDir));
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
            logger.error("Error executing the JAR file: {}", fileName, e);
        }
    }

    public static void downloadMrPack(String fileURL) {
        String outputFile = "modrinth.index.json";

        try {
            logger.info("Downloading .mrpack file from: {}", fileURL);
            URI uri = new URI(fileURL);
            URL url = uri.toURL();
            HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
            httpConn.setRequestMethod("GET");
            httpConn.setConnectTimeout(5000);
            httpConn.setReadTimeout(5000);

            int responseCode = httpConn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = httpConn.getInputStream();
                Path outputPath = Path.of(currentDir, "temp.mrpack");
                Files.copy(inputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
                inputStream.close();
                logger.info("Download completed successfully: {}", outputPath);

                unzipMrPack(outputPath.toString());

                if (!Files.exists(Path.of(currentDir, outputFile))) {
                    logger.error("An error occurred: temp.mrpack does not contain a valid modrinth.index.json file.");
                    return;
                }

                logger.info("Extraction completed. Processing {}...", outputFile);
                processModrinthIndex(outputFile);

                copyOverrides();

                Files.deleteIfExists(outputPath);
                Files.deleteIfExists(Path.of(currentDir,"modrinth.index.json"));
                logger.info("Temporary file temp.mrpack deleted.");
            } else {
                logger.error("Failed to download the file: Server returned response code {}", responseCode);
            }
            httpConn.disconnect();
        } catch (IOException | URISyntaxException e) {
            logger.error("Error downloading or extracting the .mrpack file: {}", e.getMessage());
        }
    }

    private static void processModrinthIndex(String jsonFile) {
        logger.info("Processing {}...", jsonFile);

        try (Reader reader = Files.newBufferedReader(Path.of(jsonFile))) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            String name = jsonObject.get("name").getAsString();
            String versionId = jsonObject.get("versionId").getAsString();

            logger.info("Installing modpack {} version {}...", name, versionId);

            JsonArray files = jsonObject.getAsJsonArray("files");

            for (int i = 0; i < files.size(); i++) {
                JsonObject fileObject = files.get(i).getAsJsonObject();
                String downloadUrl = fileObject.getAsJsonArray("downloads").get(0).getAsString();
                String path = fileObject.get("path").getAsString();

                JsonObject env = fileObject.getAsJsonObject("env");

                if (path.contains("mods/") && "required".equals(env.get("server").getAsString())) {
                    Path outputPath = Path.of(System.getProperty("user.dir"), path);
                    Files.createDirectories(outputPath.getParent());

                    logger.info("Downloading {}...", path);
                    try (InputStream in = new URL(downloadUrl).openStream()) {
                        Files.copy(in, outputPath, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Downloaded {} to {}", path, outputPath);
                    } catch (IOException e) {
                        logger.error("Failed to download {}: {}", path, e.getMessage());
                    }
                } else {
                    logger.info("Skipping {}: not a required mod or does not belong to mods directory.", path);
                }
            }
        } catch (IOException e) {
            logger.error("Error processing {}: {}", jsonFile, e.getMessage());
        }
    }

    private static void copyOverrides() {
        Path modsSource = Path.of(currentDir, "overrides", "mods");
        Path configSource = Path.of(currentDir, "overrides", "config");
        Path modsDestination = Path.of(currentDir, "mods");
        Path configDestination = Path.of(currentDir, "config");
        Path overridesSource = Path.of(currentDir, "overrides");

        try {
            if (Files.exists(modsSource)) {
                if (!Files.exists(modsDestination)) {
                    Files.createDirectories(modsDestination);
                }
                Files.walk(modsSource).forEach(source -> {
                    Path dest = modsDestination.resolve(modsSource.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                        logger.info("Copied mod: {}", dest);
                    } catch (IOException e) {
                        logger.error("Failed to copy mod {}: {}", source, e.getMessage());
                    }
                });
            } else {
                logger.warn("Mods source directory does not exist: {}", modsSource);
            }

            if (Files.exists(configSource)) {
                if (!Files.exists(configDestination)) {
                    Files.createDirectories(configDestination);
                }
                Files.walk(configSource).forEach(source -> {
                    Path dest = configDestination.resolve(configSource.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                        logger.info("Copied config: {}", dest);
                    } catch (IOException e) {
                        logger.error("Failed to copy config {}: {}", source, e.getMessage());
                    }
                });
            } else {
                logger.warn("Config source directory does not exist: {}", configSource);
            }

            deleteDirectoryRecursively(overridesSource);
            logger.info("Deleted the overrides directory successfully.");

        } catch (IOException e) {
            logger.error("Error while copying / deleting overrides: {}", e.getMessage());
        }
    }

    private static void deleteDirectoryRecursively(Path dirPath) throws IOException {
        Files.walk(dirPath)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        logger.error("Failed to delete {}: {}", path, e.getMessage());
                    }
                });
    }

    private static void unzipMrPack(String mrPackFilePath) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(mrPackFilePath))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                File file = new File(currentDir, entry.getName());
                if (entry.isDirectory()) {
                    if (!file.isDirectory() && !file.mkdirs()) {
                        logger.warn("Failed to create directory: {}", file);
                    }
                } else {
                    file.getParentFile().mkdirs();

                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                            bos.write(buffer, 0, bytesRead);
                        }
                    }
                    logger.info("Extracted file: {}", file);
                }
                zipInputStream.closeEntry();
            }
        } catch (IOException e) {
            logger.error("Error while extracting .mrpack file: {}", e.getMessage());
        }
    }

    private static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        private final String streamType;

        public StreamGobbler(InputStream inputStream, String streamType) {
            this.inputStream = inputStream;
            this.streamType = streamType;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info(line);
                }
            } catch (IOException e) {
                logger.error("Error reading {} stream: {}", streamType, e.getMessage());
            }
        }
    }
}

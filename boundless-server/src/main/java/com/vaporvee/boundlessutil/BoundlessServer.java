package com.vaporvee.boundlessutil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class BoundlessServer {
    private static final Logger logger = LogManager.getLogger(BoundlessServer.class);

    public static void main(String[] args) {
        logger.info("Downloading and installing NeoForge Server");
        String neoForgeVersion = "21.1.62";
        downloadAndExecuteJar(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neoForgeVersion + "/neoforge-" + neoForgeVersion + "-installer.jar",
                "installer.jar", new String[]{"--install-server"});
        logger.info("↑ Yeah no I'll do that for you automatically...");
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

package com.pais.handy.packaging;

import groovy.json.JsonException;
import groovy.json.JsonSlurper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Owns the installable Handy APKs that CodeMemo can list in this checkout. */
public final class ApkDownloads {
    private static final Pattern HANDY_APK = Pattern.compile(
        "Handy(?:-Android|-v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.+-]+)?)?\\.apk"
    );
    private static final List<String> DOWNLOAD_DIRS = List.of(
        "", "android/app/build/outputs/apk/debug", "android/app/build/outputs/apk/release",
        "android/downloads", "public/downloads", "dist/downloads"
    );

    private ApkDownloads() {}

    public static List<Path> otherDownloads(Path root, Path selected) throws IOException {
        List<Path> others = new ArrayList<>();
        Path realRoot = root.toRealPath();
        for (String location : DOWNLOAD_DIRS) {
            Path dir = root.resolve(location);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !dir.toRealPath().startsWith(realRoot)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.apk")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    boolean oldGradleName = location.startsWith("android/app/build/outputs/apk/") &&
                        name.equals("app-" + dir.getFileName() + ".apk");
                    if (!file.equals(selected) && (HANDY_APK.matcher(name).matches() || oldGradleName)
                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        others.add(file);
                    }
                }
            }
        }
        return others;
    }

    public static synchronized void publish(Path source, Path selected) throws IOException {
        Path root = selected.getParent();
        if (!HANDY_APK.matcher(selected.getFileName().toString()).matches()) {
            throw new IOException("Unexpected Handy APK name: " + selected.getFileName());
        }
        Path lockDir = Files.createDirectories(root.resolve("android/.gradle"));
        try (FileChannel channel = FileChannel.open(lockDir.resolve("handy-apk.lock"),
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            Path staged = Files.createTempFile(root, ".handy-apk-", ".part");
            Map<Path, Path> retired = new LinkedHashMap<>();
            try {
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
                try (ZipFile apk = new ZipFile(staged.toFile())) {
                    if (apk.getEntry("AndroidManifest.xml") == null) throw new IOException("Incomplete Android APK");
                }
                preserveLegacyRelease(root);
                for (Path old : otherDownloads(root, selected)) {
                    Path backup = Files.createTempFile(root, ".handy-previous-", ".part");
                    try {
                        move(old, backup);
                    } catch (IOException error) {
                        Files.deleteIfExists(backup);
                        throw error;
                    }
                    retired.put(old, backup);
                }
                move(staged, selected);
            } catch (IOException error) {
                for (Map.Entry<Path, Path> entry : retired.entrySet()) {
                    try {
                        move(entry.getValue(), entry.getKey());
                    } catch (IOException restoreError) {
                        error.addSuppressed(restoreError);
                    }
                }
                throw error;
            } finally {
                Files.deleteIfExists(staged);
            }
            for (Path backup : retired.values()) Files.deleteIfExists(backup);
        }
    }

    // Migrate a previously published root APK before its public name is replaced.
    private static void preserveLegacyRelease(Path root) throws IOException {
        Path outputs = root.resolve("android/app/build/outputs");
        Path marker = outputs.resolve("verified-release.json");
        if (!Files.isRegularFile(marker)) return;
        Object record;
        try {
            record = new JsonSlurper().parse(marker.toFile());
        } catch (JsonException malformed) {
            return;
        }
        if (!(record instanceof Map<?, ?> data) || !(data.get("fileName") instanceof String name)
            || !HANDY_APK.matcher(name).matches() || !(data.get("sha256") instanceof String sha256)
            || !sha256.matches("[0-9a-f]{64}")) return;
        Path snapshot = outputs.resolve("verified-apks").resolve(sha256 + ".bin");
        Path legacy = root.resolve(name);
        if (Files.isRegularFile(snapshot) || !Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)) return;
        Files.createDirectories(snapshot.getParent());
        Path staged = Files.createTempFile(snapshot.getParent(), "legacy-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(legacy), digest);
                 OutputStream output = Files.newOutputStream(staged)) {
                input.transferTo(output);
            }
            if (HexFormat.of().formatHex(digest.digest()).equals(sha256)) move(staged, snapshot);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

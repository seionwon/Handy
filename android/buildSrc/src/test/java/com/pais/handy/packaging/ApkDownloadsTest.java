package com.pais.handy.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ApkDownloadsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path input(String text) throws IOException {
        Path file = temporary.newFile().toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test public void successfulBuildRetiresOnlyHandyInstallersIncludingLegacyOutputs() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path old = Files.writeString(root.resolve("Handy-v0.10.1.apk"), "old");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60000));
        Path gradle = Files.createDirectories(root.resolve("android/app/build/outputs/apk/debug"));
        Files.writeString(gradle.resolve("app-debug.apk"), "old");
        Path web = Files.createDirectories(root.resolve("public/downloads"));
        Files.writeString(web.resolve("Handy-Android.apk"), "old");
        Path other = Files.writeString(web.resolve("Other.apk"), "keep");
        Path source = input("current");
        Path target = root.resolve("Handy-v0.10.2-seionwon-debug.apk");
        ApkDownloads.publish(source, target);
        ApkDownloads.publish(source, target);
        assertEquals(-1L, Files.mismatch(source, target));
        assertEquals(List.of(), ApkDownloads.otherDownloads(root, target));
        assertFalse(Files.exists(old));
        assertEquals("keep", Files.readString(other));
    }

    @Test public void failedBuildKeepsTheLastGoodDownload() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path previous = input("last good");
        Path target = root.resolve("Handy-v0.10.2-seionwon-debug.apk");
        ApkDownloads.publish(previous, target);
        Path partial = Files.writeString(temporary.newFile().toPath(), "unfinished");
        assertThrows(IOException.class, () -> ApkDownloads.publish(partial, target));
        assertThrows(IOException.class, () -> ApkDownloads.publish(partial, root.resolve("Handy-v0.10.3-seionwon.apk")));
        assertEquals(-1L, Files.mismatch(previous, target));
        assertTrue(ApkDownloads.otherDownloads(root, target).isEmpty());
    }

    @Test public void laterDebugBuildKeepsPreviouslyPublishedReleaseBytesAvailable() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path outputs = Files.createDirectories(root.resolve("android/app/build/outputs"));
        Path release = input("older release");
        String name = "Handy-v0.10.2-seionwon.apk";
        Files.copy(release, root.resolve(name));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(release)));
        Files.writeString(outputs.resolve("verified-release.json"), "{\"fileName\":\"" + name + "\",\"sha256\":\"" + hash + "\"}");
        Path target = root.resolve("Handy-v0.10.2-seionwon-debug.apk");
        ApkDownloads.publish(input("newer debug"), target);
        assertEquals(-1L, Files.mismatch(release, outputs.resolve("verified-apks/" + hash + ".bin")));
        assertFalse(Files.exists(root.resolve(name)));
        assertTrue(Files.isRegularFile(target));
    }

    @Test public void failedReplacementRestoresThePreviousDownload() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path previous = input("last good");
        Path old = root.resolve("Handy-v0.10.2-seionwon-debug.apk");
        Files.copy(previous, old);
        Path blocked = Files.createDirectory(root.resolve("Handy-v0.10.3-seionwon-debug.apk"));
        Files.writeString(blocked.resolve("unrelated"), "keep");
        Path updated = input("new build");
        assertThrows(IOException.class, () -> ApkDownloads.publish(updated, blocked));
        assertEquals(-1L, Files.mismatch(previous, old));
        assertEquals("keep", Files.readString(blocked.resolve("unrelated")));
    }

    @Test public void invalidLegacyRecordStillAllowsALocalBuild() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path outputs = Files.createDirectories(root.resolve("android/app/build/outputs"));
        Files.writeString(outputs.resolve("verified-release.json"), "{incomplete");
        Path target = root.resolve("Handy-v0.10.2-seionwon-debug.apk");
        Path source = input("current");
        ApkDownloads.publish(source, target);
        assertEquals(-1L, Files.mismatch(source, target));
        assertFalse(Files.exists(outputs.resolve("verified-apks")));
    }

    @Test public void overlappingBuildsLeaveOneCompleteMatchingDownload() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path a = input("debug");
        Path b = input("release");
        Path debug = root.resolve("Handy-v0.10.2-seionwon-debug.apk");
        Path release = root.resolve("Handy-v0.10.2-seionwon.apk");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { ApkDownloads.publish(a, debug); return true; });
            var two = pool.submit(() -> { ApkDownloads.publish(b, release); return true; });
            assertTrue(one.get());
            assertTrue(two.get());
        } finally {
            pool.shutdownNow();
        }
        assertNotEquals(Files.exists(debug), Files.exists(release));
        assertEquals(-1L, Files.exists(debug) ? Files.mismatch(a, debug) : Files.mismatch(b, release));
    }
}

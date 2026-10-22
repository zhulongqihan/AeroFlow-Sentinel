package io.github.zhulongqihan.aeroflow.sentinel.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SafeUploadStorageTest {
    @TempDir Path directory;
    private final SafeUploadStorage storage = new SafeUploadStorage();

    @Test
    void replacesSameNameOnlyAfterCompleteWrite() throws Exception {
        Path root = directory.resolve("uploads");
        storage.store(root, "guide.md", bytes("old"));
        Path target = storage.store(root, "guide.md", bytes("new complete content"));
        assertEquals("new complete content", Files.readString(target));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test
    void rejectsPathsInBothOperatingSystemFormats() {
        for (String filename : new String[]{"../outside.md", "..\\outside.md", "/tmp/a.md", "C:\\a.md",
                "nested/a.md", "nested\\a.md", "a:b.md", ".", "..", " a.md", "a.md "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> storage.store(directory, filename, bytes("bad")), filename);
        }
    }

    @Test
    void failedUploadPreservesPreviousContentAndCleansStaging() throws Exception {
        Path root = directory.resolve("uploads");
        storage.store(root, "guide.md", bytes("valid previous version"));
        InputStream interrupted = new InputStream() {
            private int calls;
            @Override public int read() throws IOException {
                if (calls++ < 3) return 'x';
                throw new IOException("simulated upload failure");
            }
        };
        assertThrows(IOException.class, () -> storage.store(root, "guide.md", interrupted));
        assertEquals("valid previous version", Files.readString(root.resolve("guide.md")));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test
    void refusesExistingDirectoryAsTarget() throws Exception {
        Path root = directory.resolve("uploads");
        Files.createDirectories(root.resolve("guide.md"));
        assertThrows(IllegalArgumentException.class, () -> storage.store(root, "guide.md", bytes("bad")));
        assertTrue(Files.isDirectory(root.resolve("guide.md")));
    }

    @Test
    void refusesTargetSymlinkAndLeavesOutsideFileUntouched() throws Exception {
        Path root = Files.createDirectories(directory.resolve("uploads"));
        Path outside = Files.writeString(directory.resolve("outside.md"), "outside");
        createLinkOrSkip(root.resolve("guide.md"), outside);
        assertThrows(IllegalArgumentException.class, () -> storage.store(root, "guide.md", bytes("bad")));
        assertEquals("outside", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(root.resolve("guide.md")));
    }

    @Test
    void refusesLinkedRootAndLinkedParent() throws Exception {
        Path real = Files.createDirectories(directory.resolve("real"));
        Path link = directory.resolve("link");
        createLinkOrSkip(link, real);
        assertThrows(IllegalArgumentException.class, () -> storage.store(link, "guide.md", bytes("bad")));
        assertThrows(IllegalArgumentException.class, () -> storage.store(link.resolve("nested"), "guide.md", bytes("bad")));
        assertFalse(Files.exists(real.resolve("guide.md")));
        assertFalse(Files.exists(real.resolve("nested")));
    }

    private void createLinkOrSkip(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "Host does not permit filesystem symlinks: " + unavailable.getClass().getSimpleName());
        }
    }

    private InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}

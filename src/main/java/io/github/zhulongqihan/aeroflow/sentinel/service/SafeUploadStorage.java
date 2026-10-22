package io.github.zhulongqihan.aeroflow.sentinel.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Same-directory staging preserves the previous file until an atomic replacement succeeds. */
@Service
public class SafeUploadStorage {
    private final ReentrantLock[] stripes = new ReentrantLock[64];

    public SafeUploadStorage() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock();
    }

    public Path store(Path configuredRoot, String filename, InputStream content) throws IOException {
        try (InputStream input = content) {
            return storeContent(configuredRoot, filename, input);
        }
    }

    private Path storeContent(Path configuredRoot, String filename, InputStream input) throws IOException {
        validateFilename(filename);
        Path root = configuredRoot.toAbsolutePath().normalize();
        for (Path parent = root; parent != null; parent = parent.getParent()) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || !parent.equals(parent.toRealPath()))) {
                throw new IllegalArgumentException("上传目录不允许使用链接路径");
            }
        }
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        // Upload roots and their ancestors must not redirect writes through filesystem links.
        if (!root.equals(realRoot)) throw new IllegalArgumentException("上传目录不允许使用链接路径");
        Path target = realRoot.resolve(filename).normalize();
        if (!target.getParent().equals(realRoot)) throw new IllegalArgumentException("无效的文件名");
        ReentrantLock lock = stripes[(target.hashCode() & Integer.MAX_VALUE) % stripes.length];
        lock.lock();
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(realRoot)) {
            if (directory instanceof SecureDirectoryStream<Path> secure) {
                storeSecure(secure, Path.of(filename), input);
            } else {
                storeAtomic(root, realRoot, target, input);
            }
            return target;
        } finally {
            lock.unlock();
        }
    }

    private void validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\") || filename.contains(":")
                || filename.indexOf('\0') >= 0 || !filename.equals(filename.strip())) {
            throw new IllegalArgumentException("文件名必须是不含路径的普通名称");
        }
    }

    private void storeSecure(SecureDirectoryStream<Path> directory, Path target, InputStream input) throws IOException {
        Path temporary = Path.of(".upload-" + UUID.randomUUID() + ".part");
        try {
            checkTarget(directory, target);
            try (SeekableByteChannel channel = directory.newByteChannel(temporary,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                input.transferTo(Channels.newOutputStream(channel));
            }
            checkTarget(directory, target);
            // SecureDirectoryStream uses directory handles, avoiding parent-link substitution races.
            directory.move(temporary, directory, target);
        } finally {
            try { directory.deleteFile(temporary); }
            catch (java.nio.file.NoSuchFileException ignored) { }
        }
    }

    private void checkTarget(SecureDirectoryStream<Path> directory, Path target) throws IOException {
        try {
            BasicFileAttributes attributes = directory.getFileAttributeView(target,
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IllegalArgumentException("目标必须是普通文件，不能是链接或目录");
            }
        } catch (java.nio.file.NoSuchFileException absent) {
            // A new safe filename is allowed.
        }
    }

    private void storeAtomic(Path configuredRoot, Path realRoot, Path target, InputStream input) throws IOException {
        checkTarget(target);
        Path temporary = Files.createTempFile(realRoot, ".upload-", ".part");
        try {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!configuredRoot.toRealPath().equals(realRoot) || !target.getParent().toRealPath().equals(realRoot)) {
                throw new IllegalArgumentException("上传目录在写入期间发生变化");
            }
            checkTarget(target);
            // Fail closed if this filesystem cannot replace atomically; never delete the old file first.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void checkTarget(Path target) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标必须是普通文件，不能是链接或目录");
        }
    }
}

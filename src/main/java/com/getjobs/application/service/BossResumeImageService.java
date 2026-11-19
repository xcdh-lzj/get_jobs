package com.getjobs.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 管理 Boss 图片简历。文件保存在程序启动目录之外，不写入 classpath 或 JAR。
 */
@Service
@Slf4j
public class BossResumeImageService {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final String PREVIEW_URL = "/api/boss/config/resume-image/content";

    private static final List<String> SUPPORTED_EXTENSIONS = List.of("png", "jpg", "gif");
    private final Path storageDirectory;

    public BossResumeImageService() {
        this(Paths.get(System.getProperty("user.dir"), "data", "boss", "resume"));
    }

    BossResumeImageService(Path storageDirectory) {
        this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
    }

    private Path storageDirectory() {
        return storageDirectory(BossAccountContext.id());
    }

    private Path storageDirectory(long accountId) {
        return accountId == 1 ? storageDirectory
                : storageDirectory.resolve("accounts").resolve(Long.toString(accountId));
    }

    public synchronized ResumeImageInfo save(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片简历");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }

        Files.createDirectories(storageDirectory());
        Path temporaryFile = Files.createTempFile(storageDirectory(), "resume-upload-", ".tmp");
        try {
            file.transferTo(temporaryFile);
            if (Files.size(temporaryFile) > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("图片大小不能超过 10MB");
            }

            String extension = detectImageExtension(temporaryFile);
            Path target = storageDirectory().resolve("resume." + extension);
            moveReplacing(temporaryFile, target);
            deleteOtherResumeImages(target);
            return toInfo(target);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public synchronized Optional<ResumeImageInfo> getCurrentImage() {
        return findCurrentImagePath().map(path -> {
            try {
                return toInfo(path);
            } catch (IOException e) {
                return null;
            }
        });
    }

    public synchronized Optional<Path> getCurrentImagePath() {
        return findCurrentImagePath();
    }

    /** 数据库提交前暂存目标原图，配置保存失败时恢复图片。 */
    synchronized <T> T withSyncedImage(long sourceId, long targetId, Supplier<T> saveConfig) throws IOException {
        Path sourceDirectory = storageDirectory(sourceId);
        Path targetDirectory = storageDirectory(targetId);
        Path sourceImage = findCurrentImagePath(sourceDirectory).orElse(null);
        if (sourceImage == null && !existingImagePaths(sourceDirectory).isEmpty()) {
            throw new IOException("来源账号的图片简历不可读");
        }
        var originals = existingImagePaths(targetDirectory);
        if (sourceImage == null && originals.isEmpty()) return saveConfig.get();
        if (Files.exists(sourceDirectory) && Files.exists(targetDirectory)
                && Files.isSameFile(sourceDirectory, targetDirectory)) {
            throw new IOException("来源账号和目标账号的图片目录相同");
        }

        Files.createDirectories(targetDirectory);
        Path staging = Files.createTempDirectory(targetDirectory, ".resume-sync-");
        Path incoming = staging.resolve("incoming");
        Path destination = sourceImage == null ? null : targetDirectory.resolve(sourceImage.getFileName());
        var archived = new ArrayList<Path>();
        boolean installed = false;
        boolean keepBackup = false;
        try {
            if (sourceImage != null) Files.copy(sourceImage, incoming);
            for (Path original : originals) {
                if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("目标账号的图片简历路径不是普通文件");
                }
                moveReplacing(original, staging.resolve(original.getFileName()));
                archived.add(original);
            }
            if (destination != null) {
                moveReplacing(incoming, destination);
                installed = true;
            }
            return saveConfig.get();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (installed) Files.deleteIfExists(destination);
                for (Path original : archived) moveReplacing(staging.resolve(original.getFileName()), original);
            } catch (IOException recoveryError) {
                keepBackup = true;
                failure.addSuppressed(recoveryError);
                log.error("同步失败后恢复图片简历时遇到文件错误，原图备份保留在 {}", staging, recoveryError);
            }
            throw failure;
        } finally {
            if (!keepBackup) removeStaging(staging);
        }
    }

    private List<Path> existingImagePaths(Path directory) {
        return SUPPORTED_EXTENSIONS.stream().map(extension -> directory.resolve("resume." + extension))
                .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS)).toList();
    }

    private void removeStaging(Path directory) {
        try {
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path file : files) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException error) {
            log.warn("清理图片简历同步暂存目录失败", error);
        }
    }

    public Map<String, Object> getMetadata() {
        return getCurrentImage()
                .map(info -> Map.<String, Object>of(
                        "exists", true,
                        "fileName", info.fileName(),
                        "contentType", info.contentType(),
                        "size", info.size(),
                        "updatedAt", info.updatedAt(),
                        "previewUrl", PREVIEW_URL + "?accountId=" + BossAccountContext.id()
                ))
                .orElseGet(() -> Map.of("exists", false));
    }

    private Optional<Path> findCurrentImagePath() {
        return findCurrentImagePath(storageDirectory());
    }

    private Optional<Path> findCurrentImagePath(Path directory) {
        for (String extension : SUPPORTED_EXTENSIONS) {
            Path candidate = directory.resolve("resume." + extension);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private String detectImageExtension(Path image) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) {
                throw new IllegalArgumentException("无法读取图片文件");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("文件不是有效的 PNG、JPG 或 GIF 图片");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                // 读取尺寸会让 ImageIO 实际解析图片头，避免只相信文件名或 Content-Type。
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("图片尺寸无效");
                }
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                return switch (format) {
                    case "png" -> "png";
                    case "jpeg", "jpg" -> "jpg";
                    case "gif" -> "gif";
                    default -> throw new IllegalArgumentException("仅支持 PNG、JPG、JPEG 和 GIF 图片");
                };
            } finally {
                reader.dispose();
            }
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteOtherResumeImages(Path retained) throws IOException {
        for (String extension : SUPPORTED_EXTENSIONS) {
            Path candidate = storageDirectory().resolve("resume." + extension);
            if (!candidate.equals(retained)) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private ResumeImageInfo toInfo(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String contentType = switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
        return new ResumeImageInfo(
                path,
                fileName,
                contentType,
                Files.size(path),
                Files.getLastModifiedTime(path).toMillis()
        );
    }

    public record ResumeImageInfo(Path path, String fileName, String contentType, long size, long updatedAt) {
    }
}

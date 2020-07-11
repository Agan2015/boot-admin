package com.hb0730.boot.admin.oss.handler.impl;

import com.hb0730.boot.admin.commons.constant.enums.AttachmentTypeEnum;
import com.hb0730.boot.admin.commons.utils.ImageUtils;
import com.hb0730.boot.admin.configuration.properties.BootAdminProperties;
import com.hb0730.boot.admin.exception.file.FileOperationException;
import com.hb0730.boot.admin.exception.file.FileUploadException;
import com.hb0730.boot.admin.oss.configuration.properties.OssProperties;
import com.hb0730.boot.admin.oss.configuration.properties.impl.LocalOssProperties;
import com.hb0730.boot.admin.oss.handler.OssHandler;
import com.hb0730.boot.admin.oss.model.UploadResult;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.velocity.shaded.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static com.hb0730.boot.admin.commons.constant.SystemConstants.FILE_SEPARATOR;

/**
 * <p>
 * 本地上传
 * </P>
 *
 * @author bing_huang
 * @since V1.0
 */
public class LocalOssHandler implements OssHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalOssHandler.class);
    /**
     * 文件上传路径前缀
     */
    private final static String UPLOAD_SUB_DIR = "upload/";

    /**
     * 缩略图后缀
     */
    private final static String THUMBNAIL_SUFFIX = "-thumbnail";

    /**
     * 缩略图宽
     */
    private final static int THUMB_WIDTH = 256;

    /**
     * 缩略图高
     */
    private final static int THUMB_HEIGHT = 256;
    /**
     * 上传路径
     */
    private final String fileProfile;

    ReentrantLock lock = new ReentrantLock();

    public LocalOssHandler(OssProperties ossProperties, BootAdminProperties properties) {
        LocalOssProperties fileProperties = (LocalOssProperties) ossProperties;
        fileProfile = OssHandler.normalizeDirectory(fileProperties.getProfile());
        if (AttachmentTypeEnum.LOCAL.equals(properties.getAttachmentType())) {
            checkWorkDir();
        }
    }

    /**
     * 检查上传路径是可读可写
     */
    private void checkWorkDir() {
        Path path = Paths.get(fileProfile);
        if (!Files.isDirectory(path)) {
            throw new FileUploadException("%s ,不是文件目录", path);
        }
        if (!Files.isReadable(path)) {
            throw new FileUploadException("%s ,不可读", path);
        }
        if (!Files.isWritable(path)) {
            throw new FileUploadException("%s ,不可写", path);
        }
    }

    @NonNull
    @Override
    public UploadResult upload(@NonNull MultipartFile file) {
        Calendar current = Calendar.getInstance();
        // Get month and day of month
        int year = current.get(Calendar.YEAR);
        int month = current.get(Calendar.MONTH) + 1;

        // Build directory
        String subDir = UPLOAD_SUB_DIR + year + FILE_SEPARATOR + month + FILE_SEPARATOR;
        // d:/sss/eds.txt
        String originalFilename = file.getOriginalFilename();
        // d:/sss/eds.txt -> eds
        String originalBasename = FilenameUtils.getBaseName(originalFilename);

        String baseName = originalBasename + "-" + UUID.randomUUID().toString();

        // d:/sss/eds.txt -> txt
        String extension = FilenameUtils.getExtension(originalFilename);
        LOGGER.debug("Base name: [{}], extension: [{}] of original filename: [{}]", baseName, extension, file.getOriginalFilename());

        // Build sub file path
        String subFilePath = subDir + baseName + '.' + extension;

        // Get upload path
        Path uploadPath = Paths.get(fileProfile, subFilePath);

        LOGGER.info("Uploading to directory: [{}]", uploadPath.toString());


        try {
            // TODO Synchronize here
            // Create directory
            Files.createDirectories(uploadPath.getParent());
            Files.createFile(uploadPath);

            // Upload this file
            file.transferTo(uploadPath);

            // Build upload result
            UploadResult uploadResult = new UploadResult();
            uploadResult.setFilename(originalBasename);
            uploadResult.setFilePath(subFilePath);
            uploadResult.setKey(subFilePath);
            uploadResult.setSuffix(extension);
            uploadResult.setMediaType(MediaType.valueOf(Objects.requireNonNull(file.getContentType())));
            uploadResult.setSize(file.getSize());

            // TODO refactor this: if image is svg ext. extension
            boolean isSvg = "svg".equals(extension);

            // Check file type
            if (OssHandler.isImageType(uploadResult.getMediaType()) && !isSvg) {
                lock.lock();
                try {
                    // Upload a thumbnail
                    String thumbnailBasename = baseName + THUMBNAIL_SUFFIX;
                    String thumbnailSubFilePath = subDir + thumbnailBasename + '.' + extension;
                    Path thumbnailPath = Paths.get(fileProfile + thumbnailSubFilePath);

                    // Read as image
                    BufferedImage originalImage = ImageUtils.getImageFromFile(new FileInputStream(uploadPath.toFile()), extension);
                    // Set width and height
                    uploadResult.setWidth(originalImage==null?null:originalImage.getWidth());
                    uploadResult.setHeight(originalImage==null?null:originalImage.getHeight());

                    // Generate thumbnail
                    boolean result = generateThumbnail(originalImage, thumbnailPath, extension);
                    if (result) {
                        // Set thumb path
                        uploadResult.setThumbPath(thumbnailSubFilePath);
                    } else {
                        // If generate error
                        uploadResult.setThumbPath(subFilePath);
                    }
                } finally {
                    lock.unlock();
                }
            } else {
                uploadResult.setThumbPath(subFilePath);
            }

            return uploadResult;
        } catch (IOException e) {
            LOGGER.error("Failed to upload file to local {} ", uploadPath, e);
            throw new FileUploadException("附件上传失败 %s", e, uploadPath);
        }
    }

    @Override
    public void delete(@NonNull String key) {
        // Get path
        Path path = Paths.get(fileProfile, key);


        // Delete the file key
        try {
            Files.delete(path);
        } catch (IOException e) {
//            throw new FileOperationException("附件 " + key + " 删除失败");
        }

        String basename = FilenameUtils.getBaseName(key);
        String extension = FilenameUtils.getExtension(key);

        String thumbnailName = basename + THUMBNAIL_SUFFIX + '.' + extension;

        Path thumbnailPath = Paths.get(path.getParent().toString(), thumbnailName);

        try {
            boolean deleteResult = Files.deleteIfExists(thumbnailPath);
            if (!deleteResult) {
                LOGGER.warn("Thumbnail: [{}] may not exist", thumbnailPath.toString());
            }
        } catch (IOException e) {
            LOGGER.error("附件缩略图 {} 删除失败", thumbnailName, e);
            throw new FileOperationException("附件缩略图 %s 删除失败", e, thumbnailName);
        }
    }

    @Override
    public boolean supportType(AttachmentTypeEnum type) {
        return AttachmentTypeEnum.LOCAL.equals(type);
    }

    private boolean generateThumbnail(BufferedImage originalImage, Path thumbPath, String extension) {
        Assert.notNull(originalImage, "Image must not be null");
        Assert.notNull(thumbPath, "Thumb path must not be null");


        boolean result = false;
        // Create the thumbnail
        try {
            Files.createFile(thumbPath);
            // Convert to thumbnail and copy the thumbnail
            LOGGER.debug("Trying to generate thumbnail: [{}]", thumbPath.toString());
            Thumbnails.of(originalImage).size(THUMB_WIDTH, THUMB_HEIGHT).keepAspectRatio(true).toFile(thumbPath.toFile());
            LOGGER.debug("Generated thumbnail image, and wrote the thumbnail to [{}]", thumbPath.toString());
            result = true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to generate thumbnail: [{}]", thumbPath);
        }
        return result;
    }
}

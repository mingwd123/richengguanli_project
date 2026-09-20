package com.dayliane.ticket;

import com.dayliane.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工单截图的存储、校验与生命周期。
 *
 * 安全要点（对应计划 §六）：
 * - 存储键由服务端随机生成，客户端文件名绝不进入磁盘路径；
 * - 扩展名、文件内容签名、可解码性三重校验，不信任 MIME 声明；
 * - 限制解码像素总量，防“小文件高解压”图片炸弹；
 * - 读取必须经过鉴权接口，不提供永久公共地址。
 */
@Service
public class TicketAttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(TicketAttachmentStorage.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TicketProperties properties;
    private final JdbcTemplate jdbc;

    public TicketAttachmentStorage(TicketProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public record StoredAttachment(long id, String storageKey, String mimeType, long sizeBytes, int width, int height,
                                   String originalName) {
    }

    @Transactional
    public StoredAttachment store(String uploaderType, long uploaderId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "attachment file is required");
        long size = file.getSize();
        if (size > properties.getMaxImageBytes()) {
            throw new BusinessException(400, "image exceeds the 10MB limit");
        }

        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "only JPEG, PNG and WebP images are allowed");
        }
        String mimeType = mimeTypeOf(extension);

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException ex) {
            throw new BusinessException(400, "failed to read uploaded file");
        }
        if (bytes.length != size) throw new BusinessException(400, "uploaded file size mismatch");
        verifySignature(bytes, extension);

        int[] dimensions = readDimensions(bytes, extension);
        int width = dimensions[0];
        int height = dimensions[1];
        if (width <= 0 || height <= 0) throw new BusinessException(400, "image is not decodable");
        if ((long) width * height > properties.getMaxImagePixels()) {
            throw new BusinessException(400, "image resolution is too large");
        }

        String storageKey = randomKey();
        try {
            Path target = resolve(storageKey);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException ex) {
            log.error("failed to persist ticket attachment", ex);
            throw new BusinessException(500, "failed to store attachment");
        }

        jdbc.update("insert into ticket_attachment (uploader_type,uploader_id,storage_key,original_name,mime_type,size_bytes,width,height,state) values (?,?,?,?,?,?,?,?, 'temporary')",
                uploaderType, uploaderId, storageKey, safeOriginalName(originalName), mimeType, size, width, height);
        Long id = jdbc.queryForObject("select id from ticket_attachment where storage_key=?", Long.class, storageKey);
        return new StoredAttachment(id, storageKey, mimeType, size, width, height, safeOriginalName(originalName));
    }

    public Map<String, Object> loadAttachment(long attachmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,uploader_type,uploader_id,ticket_id,message_id,storage_key,mime_type,size_bytes,width,height,state,created_at from ticket_attachment where id=?",
                attachmentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Path resolve(String storageKey) {
        String safe = storageKey.replaceAll("[^a-f0-9]", "");
        if (safe.length() != 32) throw new BusinessException(400, "invalid storage key");
        return Path.of(properties.getStorageDir(), safe.substring(0, 2), safe);
    }

    public byte[] readFile(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException ex) {
            throw new BusinessException(404, "attachment file not found");
        }
    }

    public void deleteFileQuietly(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ex) {
            log.warn("failed to delete attachment file {}", storageKey, ex);
        }
    }

    /** 删除本人未绑定的临时附件记录（文件由调用方先行删除）；归属不符或已绑定直接拒绝。 */
    @Transactional
    public void deleteTemporary(String uploaderType, long uploaderId, long attachmentId) {
        Map<String, Object> attachment = loadAttachment(attachmentId);
        if (attachment == null) throw new BusinessException(404, "attachment not found");
        if (!uploaderType.equals(String.valueOf(attachment.get("uploader_type")))
                || ((Number) attachment.get("uploader_id")).longValue() != uploaderId) {
            throw new BusinessException(403, "attachment belongs to another uploader");
        }
        if (!"temporary".equals(String.valueOf(attachment.get("state")))) {
            throw new BusinessException(400, "bound attachments cannot be deleted");
        }
        jdbc.update("delete from ticket_attachment where id=? and state='temporary'", attachmentId);
    }

    /** 绑定附件到工单/回复：校验归属、临时状态与数量，绑定后对所有已登录用户可见。 */
    @Transactional
    public void bind(long attachmentId, String uploaderType, long uploaderId, long ticketId, Long messageId,
                     String action, int maxPerContent) {
        Map<String, Object> row = loadAttachment(attachmentId);
        if (row == null) throw new BusinessException(404, "attachment not found");
        if (!uploaderType.equals(String.valueOf(row.get("uploader_type")))
                || ((Number) row.get("uploader_id")).longValue() != uploaderId) {
            throw new BusinessException(403, "attachment belongs to another uploader");
        }
        if (!"temporary".equals(String.valueOf(row.get("state")))) {
            throw new BusinessException(400, "attachment is already bound");
        }
        String countSql = messageId == null
                ? "select count(*) from ticket_attachment where ticket_id=?"
                : "select count(*) from ticket_attachment where message_id=?";
        Integer count = jdbc.queryForObject(countSql, Integer.class, messageId == null ? ticketId : messageId);
        if (count != null && count >= maxPerContent) {
            throw new BusinessException(400, "too many images for one " + action);
        }
        jdbc.update("update ticket_attachment set ticket_id=?,message_id=?,state='bound',bound_at=utc_timestamp() where id=? and state='temporary'",
                ticketId, messageId, attachmentId);
    }

    /** 校验创建/回复请求携带的附件列表：数量、归属与绑定。 */
    public void bindAll(List<Number> attachmentIds, String uploaderType, long uploaderId, long ticketId,
                        Long messageId, String action, int maxPerContent) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        if (attachmentIds.size() > maxPerContent) {
            throw new BusinessException(400, "too many images for one " + action);
        }
        for (Number id : attachmentIds) {
            bind(id == null ? 0 : id.longValue(), uploaderType, uploaderId, ticketId, messageId, action, maxPerContent);
        }
    }

    /** 定时清理超时未绑定的临时附件；只处理 state='temporary'，天然不会误删已绑定图片。 */
    @Scheduled(cron = "0 40 3 * * *")
    public void cleanupExpiredTempAttachments() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(properties.getTempAttachmentRetentionHours(), ChronoUnit.HOURS));
        int removed = 0;
        List<Map<String, Object>> expired = jdbc.queryForList(
                "select id,storage_key from ticket_attachment where state='temporary' and created_at < ?",
                cutoff);
        for (Map<String, Object> row : expired) {
            String storageKey = String.valueOf(row.get("storage_key"));
            deleteFileQuietly(storageKey);
            removed += jdbc.update("delete from ticket_attachment where id=? and state='temporary'", row.get("id"));
        }
        if (removed > 0) log.info("cleaned {} expired temporary ticket attachments", removed);
    }

    private void verifySignature(byte[] bytes, String extension) {
        boolean ok = switch (extension) {
            case "jpg", "jpeg" -> bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
            case "png" -> bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            case "webp" -> bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
        if (!ok) throw new BusinessException(400, "file content does not match its extension");
    }

    /** 读取图片尺寸；读不出即视为不可解码。WebP 依赖 imageio-webp 插件。 */
    private int[] readDimensions(byte[] bytes, String extension) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new BusinessException(400, "unsupported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return new int[]{width, height};
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new BusinessException(400, "image is not decodable");
        }
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String mimeTypeOf(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private String safeOriginalName(String name) {
        String cleaned = name.replaceAll("[\\r\\n\\\\/]", "_");
        return cleaned.length() > 200 ? cleaned.substring(cleaned.length() - 200) : cleaned;
    }

    private String randomKey() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        StringBuilder builder = new StringBuilder(32);
        for (byte b : raw) builder.append(String.format("%02x", b));
        return builder.toString();
    }
}

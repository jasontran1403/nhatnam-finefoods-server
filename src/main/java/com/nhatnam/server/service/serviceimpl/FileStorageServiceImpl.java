package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.service.FileStorageService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Log4j2
public class FileStorageServiceImpl implements FileStorageService {

    private static final String BASE_STORAGE_PATH =
            System.getProperty("user.home") + "/Desktop/nhatnam-finefoods-storage";

    private static final String PRODUCT_IMAGE_PATH       = BASE_STORAGE_PATH + "/product";
    private static final String CATEGORY_IMAGE_PATH      = BASE_STORAGE_PATH + "/category";
    private static final String INGREDIENT_IMAGE_PATH    = BASE_STORAGE_PATH + "/ingredient";
    private static final String SELLER_IMPORT_IMAGE_PATH = BASE_STORAGE_PATH + "/import-stock";
    private static final String INVENTORY_IMAGE_PATH     = BASE_STORAGE_PATH + "/inventory-management";
    private static final String LANDINGPAGE_IMAGE_PATH   = BASE_STORAGE_PATH + "/landingpage";
    private static final String EXPENSE_IMAGE_PATH       = BASE_STORAGE_PATH + "/expense-voucher";
    private static final String INCOME_IMAGE_PATH        = BASE_STORAGE_PATH + "/income-voucher";  // ← MỚI
    private static final String RECEIPT_FILE_PATH        = BASE_STORAGE_PATH + "/order-receipt";
    private static final String EVENT_IMAGE_PATH         = BASE_STORAGE_PATH + "/landingpage-events";
    private static final String PRODUCTION_IMAGE_PATH    = BASE_STORAGE_PATH + "/production-files";
    private static final String CERTIFICATE_FILE_PATH   = BASE_STORAGE_PATH + "/certificate";

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif"
    );

    public FileStorageServiceImpl() {
        initDirs();
    }

    private void initDirs() {
        try {
            Files.createDirectories(Paths.get(PRODUCT_IMAGE_PATH));
            Files.createDirectories(Paths.get(CATEGORY_IMAGE_PATH));
            Files.createDirectories(Paths.get(INGREDIENT_IMAGE_PATH));
            Files.createDirectories(Paths.get(SELLER_IMPORT_IMAGE_PATH));
            Files.createDirectories(Paths.get(INVENTORY_IMAGE_PATH));
            Files.createDirectories(Paths.get(LANDINGPAGE_IMAGE_PATH));
            Files.createDirectories(Paths.get(EXPENSE_IMAGE_PATH));
            Files.createDirectories(Paths.get(INCOME_IMAGE_PATH));   // ← MỚI
            Files.createDirectories(Paths.get(RECEIPT_FILE_PATH));
            Files.createDirectories(Paths.get(EVENT_IMAGE_PATH));
            Files.createDirectories(Paths.get(PRODUCTION_IMAGE_PATH));
            Files.createDirectories(Paths.get(CERTIFICATE_FILE_PATH));
        } catch (IOException e) {
            throw new RuntimeException("Could not create storage directories", e);
        }
    }

    // ════════════════════════════════════════
    // PUBLIC SAVE METHODS
    // ════════════════════════════════════════

    @Override
    public String saveInventoryImage(MultipartFile file) throws IOException {
        return saveImage(file, INVENTORY_IMAGE_PATH, "inventory-management", 1920, 1080);
    }

    @Override
    public String saveProductImage(MultipartFile file) throws IOException {
        return saveImage(file, PRODUCT_IMAGE_PATH, "product", 163, 162);
    }

    @Override
    public String saveCategoryImage(MultipartFile file) throws IOException {
        return saveImage(file, CATEGORY_IMAGE_PATH, "category", 200, 200);
    }

    @Override
    public String saveIngredientImage(MultipartFile file) throws IOException {
        return saveImage(file, INGREDIENT_IMAGE_PATH, "ingredient", 150, 150);
    }

    @Override
    public String saveExpenseImage(MultipartFile file) throws IOException {
        return saveImage(file, EXPENSE_IMAGE_PATH, "expense-voucher", 1920, 1080);
    }

    @Override
    public String saveIncomeImage(MultipartFile file) throws IOException {
        return saveImage(file, INCOME_IMAGE_PATH, "income-voucher", 1920, 1080);
    }

    @Override
    public String saveSellerImportReceiptImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("File is empty");

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isValidFormat(originalFilename))
            throw new IllegalArgumentException(
                    "Invalid image format. Allowed: " + ALLOWED_EXTENSIONS);

        BufferedImage original = ImageIO.read(file.getInputStream());
        if (original == null) throw new IOException("Cannot read image file");

        BufferedImage resized = resizeKeepRatio(original, 1920);

        String filename = generateFilename("seller-import");
        Path target = Paths.get(SELLER_IMPORT_IMAGE_PATH, filename);

        if (!ImageIO.write(resized, "png", target.toFile()))
            throw new IOException("Failed to write receipt image");

        return "/images/seller-import/" + filename;
    }

    // ════════════════════════════════════════
    // CORE SAVE LOGIC
    // ════════════════════════════════════════

    private String saveImage(MultipartFile file, String directory,
                             String prefix, int targetW, int targetH) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isValidFormat(originalFilename)) {
            throw new IllegalArgumentException(
                    "Invalid image format. Allowed: " + ALLOWED_EXTENSIONS);
        }

        BufferedImage original = readImageSafely(file);
        if (original == null) throw new IOException("Cannot read image file — unsupported format or corrupted");

        boolean hasAlpha = original.getColorModel().hasAlpha();
        BufferedImage resized = resizeAndCrop(original, targetW, targetH, hasAlpha);

        String filename = generateFilename(prefix);
        Path target = Paths.get(directory, filename);

        if (!ImageIO.write(resized, "png", target.toFile())) {
            throw new IOException("Failed to write image as PNG");
        }

        return "/images/" + prefix + "/" + filename;
    }

    private BufferedImage resizeKeepRatio(BufferedImage src, int maxSide) {
        int origW = src.getWidth();
        int origH = src.getHeight();

        if (origW <= maxSide && origH <= maxSide) return src;

        double ratio = (double) maxSide / Math.max(origW, origH);
        int newW = (int) (origW * ratio);
        int newH = (int) (origH * ratio);

        boolean hasAlpha = src.getColorModel().hasAlpha();
        int imgType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        BufferedImage out = new BufferedImage(newW, newH, imgType);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!hasAlpha) { g.setColor(Color.WHITE); g.fillRect(0, 0, newW, newH); }
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();

        return out;
    }

    private BufferedImage resizeAndCrop(BufferedImage src, int w, int h, boolean hasAlpha) {
        double ratioW = (double) w / src.getWidth();
        double ratioH = (double) h / src.getHeight();
        double ratio  = Math.max(ratioW, ratioH);

        int sw = (int) (src.getWidth()  * ratio);
        int sh = (int) (src.getHeight() * ratio);

        int imgType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        BufferedImage tmp = new BufferedImage(sw, sh, imgType);
        Graphics2D g = tmp.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (hasAlpha) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f));
            g.fillRect(0, 0, sw, sh);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        } else {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, sw, sh);
        }

        g.drawImage(src, 0, 0, sw, sh, null);
        g.dispose();

        int x = Math.max(0, (sw - w) / 2);
        int y = Math.max(0, (sh - h) / 2);
        return tmp.getSubimage(x, y, w, h);
    }

    // ════════════════════════════════════════
    // DELETE / GET
    // ════════════════════════════════════════

    @Override
    public void deleteFile(String filePath) throws IOException {
        Path path = resolvePath(filePath);
        if (path == null) { log.warn("⚠️ Unknown file path: {}", filePath); return; }
        if (Files.exists(path)) Files.delete(path);
        else log.warn("⚠️ File not found: {}", filePath);
    }

    @Override
    public String saveEventImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File is empty");

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isValidFormat(originalFilename))
            throw new IllegalArgumentException("Invalid image format. Allowed: " + ALLOWED_EXTENSIONS);

        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        String filename = String.format("landingpage-events_%d_%s.%s",
                System.currentTimeMillis(), UUID.randomUUID(), ext);
        Files.write(Paths.get(EVENT_IMAGE_PATH, filename), file.getBytes());

        return "/images/landingpage-events/" + filename;
    }

    @Override
    public String saveReceiptFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File không được rỗng");
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank())
            throw new IllegalArgumentException("Tên file không hợp lệ");
        int dotIdx = originalFilename.lastIndexOf('.');
        String ext = dotIdx >= 0 ? originalFilename.substring(dotIdx + 1).toLowerCase() : "bin";
        List<String> allowed = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf");
        if (!allowed.contains(ext)) throw new IllegalArgumentException("Định dạng không hỗ trợ: " + ext);
        String filename = String.format("receipt_%d_%s.%s", System.currentTimeMillis(), UUID.randomUUID(), ext);
        Files.write(Paths.get(RECEIPT_FILE_PATH, filename), file.getBytes());

        return "/images/order-receipt/" + filename;
    }

    @Override
    public String saveCertificateFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File không được rỗng");
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank())
            throw new IllegalArgumentException("Tên file không hợp lệ");
        int dotIdx = originalFilename.lastIndexOf('.');
        String ext = dotIdx >= 0 ? originalFilename.substring(dotIdx + 1).toLowerCase() : "bin";
        List<String> allowed = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf");
        if (!allowed.contains(ext)) throw new IllegalArgumentException("Định dạng không hỗ trợ: " + ext);
        String filename = String.format("certificate_%d_%s.%s", System.currentTimeMillis(), UUID.randomUUID(), ext);
        Files.write(Paths.get(CERTIFICATE_FILE_PATH, filename), file.getBytes());
        return "/images/certificate/" + filename;
    }

    @Override
    public String saveLandingpageImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File is empty");

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isValidFormat(originalFilename))
            throw new IllegalArgumentException("Invalid image format. Allowed: " + ALLOWED_EXTENSIONS);

        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        String filename = String.format("landingpage_%d_%s.%s",
                System.currentTimeMillis(), UUID.randomUUID(), ext);
        Files.write(Paths.get(LANDINGPAGE_IMAGE_PATH, filename), file.getBytes());

        return "/images/landingpage/" + filename;
    }

    @Override
    public byte[] getFile(String filePath) throws IOException {
        Path path = resolvePath(filePath);
        if (path == null) throw new IllegalArgumentException("Invalid file path: " + filePath);
        if (!Files.exists(path)) throw new IOException("File not found: " + filePath);
        return Files.readAllBytes(path);
    }

    private Path resolvePath(String dbPath) {
        if (dbPath == null || dbPath.isEmpty()) return null;
        String[] parts = dbPath.split("/");
        if (parts.length < 4) return null;
        String type     = parts[2];
        String filename = parts[3];
        return switch (type) {
            case "product"              -> Paths.get(PRODUCT_IMAGE_PATH,       filename);
            case "category"             -> Paths.get(CATEGORY_IMAGE_PATH,      filename);
            case "ingredient"           -> Paths.get(INGREDIENT_IMAGE_PATH,    filename);
            case "seller-import"        -> Paths.get(SELLER_IMPORT_IMAGE_PATH, filename);
            case "inventory-management" -> Paths.get(INVENTORY_IMAGE_PATH,     filename);
            case "landingpage"          -> Paths.get(LANDINGPAGE_IMAGE_PATH,   filename);
            case "expense-voucher"      -> Paths.get(EXPENSE_IMAGE_PATH,       filename);
            case "income-voucher"       -> Paths.get(INCOME_IMAGE_PATH,        filename);  // ← MỚI
            case "landingpage-events"   -> Paths.get(EVENT_IMAGE_PATH,         filename);
            case "order-receipt"        -> Paths.get(RECEIPT_FILE_PATH,        filename);
            case "certificate"          -> Paths.get(CERTIFICATE_FILE_PATH,    filename);
            case "production" -> {
                // Path có thể nested: /images/production/batches/4/.../filename
                // → resolve từ PRODUCTION_IMAGE_PATH + toàn bộ subpath sau "production/"
                // VD: /images/production/batches/4/steps/step-1/step-1-abc.jpg
                //     → BASE/production-files/batches/4/steps/step-1/step-1-abc.jpg
                String subPath = dbPath.replaceFirst("^/images/production/", "");
                yield Paths.get(PRODUCTION_IMAGE_PATH, subPath.split("/"));
            }
            default                     -> null;
        };
    }

    // ════════════════════════════════════════
    // UTILS
    // ════════════════════════════════════════

    private BufferedImage readImageSafely(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (img != null) return img;

        try (javax.imageio.stream.ImageInputStream iis =
                     ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (iis == null) return null;
            var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            reader.setInput(iis, true, true);
            try {
                img = reader.read(0, reader.getDefaultReadParam());
            } catch (Exception e) {
                log.warn("[IMG] Primary read failed: {}", e.getMessage());
            } finally {
                reader.dispose();
            }
        }

        if (img != null) return img;

        try {
            img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) return null;
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
            return rgb;
        } catch (Exception e) {
            log.warn("[IMG] All read attempts failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean isValidFormat(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return ALLOWED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase());
    }

    private String generateFilename(String prefix) {
        return String.format("%s_%d_%s.png", prefix, System.currentTimeMillis(), UUID.randomUUID());
    }
}
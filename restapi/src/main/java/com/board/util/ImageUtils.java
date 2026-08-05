package com.board.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Slf4j
public class ImageUtils {

    private static final int MAX_WIDTH = 1024;
    private static final int MAX_HEIGHT = 1024;

    /**
     * 이미지를 최대 1024x1024 이하로 리사이즈
     * OpenAI Vision API 권장 크기
     */
    public static byte[] resizeIfNeeded(byte[] imageData, String mimeType) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageData));
            if (original == null) return imageData;

            int w = original.getWidth();
            int h = original.getHeight();

            // 이미 충분히 작으면 그대로 반환
            if (w <= MAX_WIDTH && h <= MAX_HEIGHT) return imageData;

            // 비율 유지하며 리사이즈
            double ratio = Math.min((double) MAX_WIDTH / w, (double) MAX_HEIGHT / h);
            int newW = (int) (w * ratio);
            int newH = (int) (h * ratio);

            BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, newW, newH, null);
            g.dispose();

            // 포맷 결정 (jpeg/png)
            String format = mimeType.contains("png") ? "png" : "jpeg";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resized, format, baos);

            byte[] result = baos.toByteArray();
            log.info("이미지 리사이즈: {}x{} → {}x{}, {}bytes → {}bytes",
                    w, h, newW, newH, imageData.length, result.length);
            return result;

        } catch (Exception e) {
            log.warn("이미지 리사이즈 실패, 원본 사용: {}", e.getMessage());
            return imageData;
        }
    }
}
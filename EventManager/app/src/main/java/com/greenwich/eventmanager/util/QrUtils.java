package com.greenwich.eventmanager.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Draws a QR code with the ZXing library (Apache 2.0, https://github.com/zxing/zxing).
 * Only the small "core" module is used: it gives a grid of black/white modules,
 * which is turned into a Bitmap here.
 */
public final class QrUtils {

    private QrUtils() { }

    public static Bitmap generate(String content, int sizePx) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");               // Vietnamese text
        hints.put(EncodeHintType.MARGIN, 1);                            // thin quiet zone
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M); // ~15% damage OK

        BitMatrix matrix = new QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
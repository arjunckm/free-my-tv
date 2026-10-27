package io.freemytv.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Gets a photo from the phone's gallery ready for the TV: turned upright (the TV only sees the file, so the phone applies the
 * camera's rotation), cut to the TV's 16:9 from the middle, and no bigger than the TV's own size, as a JPEG of a few hundred KB.
 * That is exactly what the TV would make of it, so sending it this way is quicker and the TV only has to save it.
 */
final class PhotoSender {
    private PhotoSender() {}

    /** The TV's photo size (Photos.W by Photos.H on the TV). */
    private static final int W = 2112, H = 1188;
    /** What the TV accepts in one request is about 3 MB. */
    private static final int MAX_BYTES = 2_800_000;

    /** The JPEG to send for this photo. Throws with a short reason when it can't be used. */
    static byte[] prepare(Context c, Uri uri) throws IOException {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't open that photo");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("That isn't a picture");
        boolean turned = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
        int w = turned ? bounds.outHeight : bounds.outWidth, h = turned ? bounds.outWidth : bounds.outHeight;
        // Decode smaller when the photo is much bigger than needed (a phone photo is 4000 px wide and 10 MB of pixels).
        int sample = 1;
        while (w / (sample * 2) >= W && h / (sample * 2) >= H) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't open that photo");
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        } catch (OutOfMemoryError e) {
            throw new IOException("That photo is too big for the phone to open");
        }
        if (bitmap == null) throw new IOException("That isn't a picture");

        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: break;
        }
        if (!m.isIdentity()) {
            Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (upright != bitmap) bitmap.recycle();
            bitmap = upright;
        }
        // The middle of the photo at 16:9.
        int cw = Math.min(bitmap.getWidth(), Math.round(bitmap.getHeight() * 16f / 9));
        int ch = Math.min(bitmap.getHeight(), Math.round(cw * 9f / 16));
        Bitmap crop = Bitmap.createBitmap(bitmap, (bitmap.getWidth() - cw) / 2, (bitmap.getHeight() - ch) / 2, cw, ch);
        if (crop != bitmap) bitmap.recycle();
        if (crop.getWidth() < 640 || crop.getHeight() < 360) {
            crop.recycle();
            throw new IOException("That picture is too small for a TV");
        }
        if (crop.getWidth() > W) {
            Bitmap scaled = Bitmap.createScaledBitmap(crop, W, H, true);
            if (scaled != crop) crop.recycle();
            crop = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        crop.compress(Bitmap.CompressFormat.JPEG, 88, out);
        if (out.size() > MAX_BYTES) {
            out.reset();
            crop.compress(Bitmap.CompressFormat.JPEG, 70, out);
        }
        crop.recycle();
        if (out.size() > MAX_BYTES) throw new IOException("That photo is too big to send");
        return out.toByteArray();
    }
}

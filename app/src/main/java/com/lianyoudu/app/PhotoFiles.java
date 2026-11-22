package com.lianyoudu.app;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.security.MessageDigest;

final class PhotoFiles {
  static boolean importPhoto(Context c, Store db, Uri uri) throws Exception {
    File folder = new File(c.getFilesDir(), "photos");
    if (!folder.exists() && !folder.mkdirs()) throw new IOException("无法创建照片目录");
    File temp = File.createTempFile("import-", ".tmp", c.getCacheDir());
    File jpg = File.createTempFile("photo-", ".tmp", folder);
    Bitmap image = null, scaled = null;
    try {
      try (InputStream in = c.getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(temp)) {
        if (in == null) throw new IOException("无法读取照片");
        byte[] b = new byte[16384];
        long bytes = 0;
        int n;
        while ((n = in.read(b)) != -1) {
          bytes += n;
          if (bytes > 25L * 1024 * 1024) throw new IOException("单张照片不能超过25MB");
          out.write(b, 0, n);
        }
      }
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(temp.toString(), bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("照片格式不支持");
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 1;
      while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2000)
        options.inSampleSize *= 2;
      image = BitmapFactory.decodeFile(temp.toString(), options);
      if (image == null) throw new IOException("照片解码失败");
      android.media.ExifInterface exif = new android.media.ExifInterface(temp.toString());
      int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
      Matrix matrix = new Matrix();
      switch (orientation) {
        case 2:
          matrix.setScale(-1, 1);
          break;
        case 3:
          matrix.setRotate(180);
          break;
        case 4:
          matrix.setScale(1, -1);
          break;
        case 5:
          matrix.setRotate(90);
          matrix.postScale(-1, 1);
          break;
        case 6:
          matrix.setRotate(90);
          break;
        case 7:
          matrix.setRotate(270);
          matrix.postScale(-1, 1);
          break;
        case 8:
          matrix.setRotate(270);
          break;
      }
      if (orientation >= 2 && orientation <= 8) {
        Bitmap rotated =
            Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
        if (rotated != image) {
          image.recycle();
          image = rotated;
        }
      }
      float ratio = Math.min(1f, 1600f / Math.max(image.getWidth(), image.getHeight()));
      scaled =
          Bitmap.createScaledBitmap(
              image,
              Math.max(1, Math.round(image.getWidth() * ratio)),
              Math.max(1, Math.round(image.getHeight() * ratio)),
              true);
      try (FileOutputStream out = new FileOutputStream(jpg)) {
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("照片保存失败");
        out.getFD().sync();
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new FileInputStream(jpg)) {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) != -1) digest.update(b, 0, n);
      }
      StringBuilder hex = new StringBuilder();
      for (byte b : digest.digest()) hex.append(String.format("%02x", b));
      String hash = hex.toString();
      if (db.pendingHash(hash)) return false;
      String id = hash + "-" + java.util.UUID.randomUUID();
      File dest = new File(folder, id + ".jpg");
      boolean created = false;
      if (!dest.exists()) {
        if (!jpg.renameTo(dest)) throw new IOException("照片保存失败");
        created = true;
      }
      try {
        db.photo(id, dest.getAbsolutePath());
      } catch (Exception e) {
        if (created) dest.delete();
        throw e;
      }
      return true;
    } finally {
      temp.delete();
      jpg.delete();
      if (scaled != null && scaled != image) scaled.recycle();
      if (image != null) image.recycle();
    }
  }

  static Uri saveGallery(Context c, Bitmap image) throws IOException {
    ContentValues v = new ContentValues();
    v.put(MediaStore.Images.Media.DISPLAY_NAME, "练有度-" + System.currentTimeMillis() + ".png");
    v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
    v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/练有度");
    v.put(MediaStore.Images.Media.IS_PENDING, 1);
    Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
    if (uri == null) throw new IOException("无法创建相册图片");
    try {
      try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
        if (out == null || !image.compress(Bitmap.CompressFormat.PNG, 100, out))
          throw new IOException("图片写入失败");
      }
      v.clear();
      v.put(MediaStore.Images.Media.IS_PENDING, 0);
      if (c.getContentResolver().update(uri, v, null, null) != 1)
        throw new IOException("图片发布失败");
      return uri;
    } catch (Exception | OutOfMemoryError e) {
      try {
        c.getContentResolver().delete(uri, null, null);
      } catch (RuntimeException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw new IOException("图片未保存", e);
    }
  }
}

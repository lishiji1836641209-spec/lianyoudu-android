package com.lianyoudu.app;

import android.graphics.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Bounded workers and cache; decoding never blocks a tap or retains an old Activity. */
final class ThumbLoader {
  private static final ExecutorService workers = Executors.newFixedThreadPool(2);
  private static final LruCache<String, Bitmap> cache =
      new LruCache<>(8 * 1024 * 1024) {
        protected int sizeOf(String key, Bitmap value) {
          return value.getAllocationByteCount();
        }
      };

  static void load(FitnessApp app, ImageView view, String path) {
    view.setTag(path);
    Bitmap ready = cache.get(path);
    if (ready != null) {
      view.setImageBitmap(ready);
      return;
    }
    WeakReference<ImageView> target = new WeakReference<>(view);
    workers.execute(
        () -> {
          if (target.get() == null) return;
          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inSampleSize = 4;
          Bitmap image = BitmapFactory.decodeFile(path, options);
          if (image == null) return;
          cache.put(path, image);
          app.main.post(
              () -> {
                ImageView current = target.get();
                if (current != null && path.equals(current.getTag())) current.setImageBitmap(image);
              });
        });
  }
}

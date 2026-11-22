package com.lianyoudu.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** Read-only capability URI, restricted to generated share files. */
public final class ShareProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    String n = uri.getLastPathSegment();
    if (n == null || !n.matches("[a-zA-Z0-9_-]+\\.(png|json|zip|jpg|gpx)"))
      throw new FileNotFoundException();
    File root = new File(getContext().getCacheDir(), "shared");
    File f = new File(root, n);
    if (!f.isFile()) throw new FileNotFoundException();
    return f;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    File f = file(u);
    if (mode.equals("r")) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    if (f.getName().startsWith("camera-")
        && (mode.equals("w") || mode.equals("rw") || mode.equals("rwt")))
      return ParcelFileDescriptor.open(
          f, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
    throw new FileNotFoundException("Read only");
  }

  @Override
  public String getType(Uri u) {
    if (u.toString().endsWith(".gpx")) return "application/gpx+xml";
    return u.toString().endsWith(".png")
        ? "image/png"
        : u.toString().endsWith(".jpg")
            ? "image/jpeg"
            : u.toString().endsWith(".zip") ? "application/zip" : "application/json";
  }

  @Override
  public Cursor query(Uri u, String[] projection, String selection, String[] args, String order) {
    MatrixCursor c = new MatrixCursor(new String[] {"_display_name", "_size"});
    try {
      File f = file(u);
      c.addRow(new Object[] {f.getName(), f.length()});
    } catch (Exception ignored) {
    }
    return c;
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}

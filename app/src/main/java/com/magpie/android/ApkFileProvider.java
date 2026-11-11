package com.magpie.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 给系统安装器提供 APK 的 content:// 读取通道。
 *
 * <p>这个壳不引 AndroidX，所以没有 androidx 的 FileProvider；这里手写一个
 * 最小版：只读 {@code cacheDir/apk/} 下的文件，按 URI 最后一段当文件名。
 * Android 7+ 对 file:// 抛 FileUriExposedException，装 APK 必须走
 * content:// + 临时授权。
 */
public final class ApkFileProvider extends ContentProvider {

    /** Manifest 里配置的 authority，保持一致。 */
    public static final String AUTHORITY = "com.magpie.android.fileprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File dir = new File(getContext().getCacheDir(), "apk");
        File f = new File(dir, uri.getLastPathSegment());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}

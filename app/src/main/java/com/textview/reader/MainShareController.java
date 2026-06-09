package com.textview.reader;

import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

final class MainShareController {
    private final MainActivity activity;

    MainShareController(@NonNull MainActivity activity) {
        this.activity = activity;
    }

    void shareSelectedFiles() {
        ArrayList<File> files = activity.getSelectedShareableFilesSnapshot();
        if (files.isEmpty()) {
            ShortToast.show(activity, R.string.share_failed);
            activity.exitFileSelectionMode(true);
            return;
        }
        if (files.size() == 1) {
            activity.exitFileSelectionMode(true);
            shareFile(files.get(0));
            return;
        }
        try {
            ArrayList<Uri> streams = new ArrayList<>();
            for (File file : files) {
                streams.add(getShareUriForFile(file));
            }
            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType(getCommonShareMimeType(files));
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(share, activity.getString(R.string.share)));
            activity.exitFileSelectionMode(true);
        } catch (Exception e) {
            ShortToast.show(activity, R.string.share_failed);
        }
    }

    void shareFile(@NonNull File file) {
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            ShortToast.show(activity, R.string.share_failed);
            return;
        }
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(getShareMimeType(file));
            share.putExtra(Intent.EXTRA_STREAM, getShareUriForFile(file));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(share, activity.getString(R.string.share)));
        } catch (Exception e) {
            ShortToast.show(activity, R.string.share_failed);
        }
    }

    @NonNull
    private Uri getShareUriForFile(@NonNull File file) {
        return FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
    }

    @NonNull
    private String getCommonShareMimeType(@NonNull ArrayList<File> files) {
        String first = null;
        boolean sameExact = true;
        boolean allImages = true;
        for (File file : files) {
            String mime = getShareMimeType(file);
            if (first == null) first = mime;
            else if (!first.equalsIgnoreCase(mime)) sameExact = false;
            if (!mime.toLowerCase(Locale.ROOT).startsWith("image/")) allImages = false;
        }
        if (sameExact && first != null) return first;
        if (allImages) return "image/*";
        return "*/*";
    }

    @NonNull
    private String getShareMimeType(@NonNull File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null && mime.trim().length() > 0) return mime;
        }
        return "application/octet-stream";
    }
}

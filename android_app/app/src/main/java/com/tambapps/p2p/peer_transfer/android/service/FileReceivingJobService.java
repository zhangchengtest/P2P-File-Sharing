package com.tambapps.p2p.peer_transfer.android.service;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PersistableBundle;
import androidx.core.app.NotificationCompat;

import android.provider.MediaStore;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.tambapps.p2p.fandem.FileReceiver;
import com.tambapps.p2p.fandem.exception.CorruptedFileException;
import com.tambapps.p2p.fandem.util.FileUtils;
import com.tambapps.p2p.fandem.util.OutputStreamProvider;
import com.tambapps.p2p.speer.Peer;

import com.tambapps.p2p.peer_transfer.android.R;
import com.tambapps.p2p.peer_transfer.android.analytics.CrashlyticsValues;
import com.tambapps.p2p.peer_transfer.android.service.event.TaskEventHandler;
import com.tambapps.p2p.speer.exception.HandshakeFailException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by fonkoua on 13/05/18.
 */

@SuppressLint("SpecifyJobSchedulerIdRange")
public class FileReceivingJobService extends FileJobService {

    public interface FileIntentProvider {
        PendingIntent ofUris(List<Uri> uris);
    }
    @Override
    FileTask startTask(NotificationCompat.Builder notifBuilder,
                       NotificationManager notificationManager,
                       final int notifId,
                       PersistableBundle bundle,
                       PendingIntent cancelIntent, FirebaseAnalytics analytics) {
        return new ReceivingTask(this, notifBuilder, notificationManager, notifId, cancelIntent,
                new FileIntentProvider() {
                    @Override
                    public PendingIntent ofUris(List<Uri> uris) {
                        Intent intent;
                        if (uris != null && uris.size() == 1) {
                            Uri uri = uris.get(0);
                            String mime = getContentResolver().getType(uri);

                            // Open file with user selected app
                            intent = new Intent();
                            intent.setAction(Intent.ACTION_VIEW);
                            intent.setDataAndType(uri, mime);
                        } else {
                            intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                        }
                        return PendingIntent.getActivity(FileReceivingJobService.this, notifId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                    }

                }, analytics)
                .execute(bundle.getString("peer"));
    }

    @Override
    int largeIcon() {
        return R.drawable.download2;
    }

    @Override
    int smallIcon() {
        return R.drawable.download;
    }

    static class ReceivingTask extends FileTask implements OutputStreamProvider {

        private FileReceiver fileReceiver;
        private FileIntentProvider fileIntentProvider;
        private long startTime;
        private final ContentResolver contentResolver;
        private final List<Uri> uris = new ArrayList<>();
        private final List<String> fileNames = new ArrayList<>();

        ReceivingTask(TaskEventHandler taskEventHandler, NotificationCompat.Builder notifBuilder,
                      NotificationManager notificationManager,
                      int notifId,
                      PendingIntent cancelIntent,
                      FileIntentProvider fileIntentProvider, FirebaseAnalytics analytics) {
            super(taskEventHandler, notifBuilder, notificationManager, notifId, cancelIntent, analytics);
            this.fileIntentProvider = fileIntentProvider;
            this.contentResolver = notifBuilder.mContext.getContentResolver();
        }

        @Override
        public void onTransferStarted(String fileName, long fileSize) {
            fileNames.add(fileName);
        }

        @Override
        protected void run(String... params) { //downloadPath, peer
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.setCustomKey(CrashlyticsValues.SHARING_ROLE, "RECEIVER");

            Peer peer = Peer.parse(params[0]);
            fileReceiver = new FileReceiver(this);

            getNotifBuilder().setContentTitle(getString(R.string.connecting))
                    .setContentText(getString(R.string.connecting_to, peer));
            updateNotification();

            try {
                fileReceiver.receiveFrom(peer, (OutputStreamProvider) this);
                completeNotification(uris);
                updateNotification();
                Bundle bundle = new Bundle();
                bundle.putString(FirebaseAnalytics.Param.METHOD, "RECEIVE");
                bundle.putLong("duration", System.currentTimeMillis() - startTime);
                getAnalytics().logEvent(FirebaseAnalytics.Event.SHARE, bundle);
            } catch (HandshakeFailException e) {
                finishNotification()
                        .setContentTitle(getString(R.string.couldnt_start))
                        .setContentText(e.getMessage());
            } catch (CorruptedFileException e) {
                finishNotification()
                        .setContentTitle(getString(R.string.corrupted_file))
                        .setContentText(e.getMessage());
            } catch (SocketException e) {
                NotificationCompat.Builder builder = finishNotification()
                        .setContentTitle(getString(R.string.transfer_canceled));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setStyle(notifStyle.bigText(getString(R.string.incomplete_transfer)));
                }
            } catch (SocketTimeoutException e) {
                finishNotification()
                        .setContentTitle(getString(R.string.transfer_canceled))
                        .setContentText(getString(R.string.connection_timeout));
            } catch (AsynchronousCloseException e) {
                finishNotification()
                        .setContentTitle(getString(R.string.transfer_canceled));
            } catch (IOException e) {
                Log.e("FileReceivingJobService", "error while receiving", e);
                crashlytics.recordException(e);
                finishNotification()
                        .setContentTitle(getString(R.string.transfer_aborted))
                        .setStyle(notifStyle.bigText(getString(R.string.error_occured, e.getMessage())));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getNotifBuilder().setStyle(notifStyle.bigText(getString(R.string.error_incomplete, e.getMessage())));
                }
            }
        }

        @Override
        public OutputStream newOutputStream(String fileName) throws IOException {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                // doesn't seem to be required values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                uris.add(uri);
                return contentResolver.openOutputStream(uri);
            } else {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = FileUtils.newAvailableFile(downloadDir, fileName);
                return new FileOutputStream(file);
            }
        }

        private void completeNotification(List<Uri> uris) {
            NotificationCompat.Builder builder = finishNotification()
                    .setContentTitle(getString(R.string.transfer_complete))
                    // nullable file
                    .setContentIntent(fileIntentProvider.ofUris(uris));

            getNotifBuilder().setStyle(notifStyle.bigText(getString(R.string.success_received,
                fileNames.stream().collect(Collectors.joining("\n- ", "- ", "")))));
        }

        @Override
        public void cancel() {
            fileReceiver.cancel();
            // the run() method will take care of the delete
        }

        @Override
        void dispose() {
            super.dispose();
            fileIntentProvider = null;
        }

        @Override
        public String onConnected(String remoteAddress) {
            this.startTime = System.currentTimeMillis();
            return getString(R.string.receveiving_connected,
                fileNames.stream().collect(Collectors.joining("\n- ", "- ", "")));
        }
    }
}

package tambapps.com.a2sfile_sharing.service;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.PersistableBundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.tambapps.file_sharing.TransferListener;

import java.util.Locale;

import tambapps.com.a2sfile_sharing.R;

/**
 * Created by fonkoua on 13/05/18.
 */

public abstract class FileJobService extends JobService {

    private FileTask fileTask;
    final static int SOCKET_TIMEOUT = 1000 * 90; //in ms
    private NotificationBroadcastReceiver notificationBroadcastReceiver;
    private final static String ACTION_CANCEL = FileJobService.class.getPackage().toString() + ".cancel";

    @Override
    public void onCreate() {
        super.onCreate();
        notificationBroadcastReceiver = new NotificationBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        intentFilter.addAction(ACTION_CANCEL);
        registerReceiver(notificationBroadcastReceiver, intentFilter);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {

        PersistableBundle bundle = params.getExtras();
        NotificationManager notificationManager  = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int notifId = bundle.getInt("id");
        Runnable endRunnable = new Runnable() {
            @Override
            public void run() {
                jobFinished(params, false);
            }
        };

        PendingIntent notifIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_CANCEL), 0);

        fileTask = startTask(buildNotification(notificationManager, notifId),
                notificationManager, notifId, bundle, endRunnable, notifIntent);
        return true;
    }

    @TargetApi(26)
    private void createChannel(NotificationManager notificationManager) {
        if (channelExists(notificationManager))
            return;
        NotificationChannel channel = new NotificationChannel(getClass().getName(), getClass().getName(), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("notifications for " + getClass().getName());
        channel.enableLights(false);
        notificationManager.createNotificationChannel(channel);
    }

    @TargetApi(26)
    private boolean channelExists(NotificationManager notificationManager) {
        NotificationChannel channel = notificationManager.getNotificationChannel(getClass().getName());
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private NotificationCompat.Builder buildNotification(NotificationManager notificationManager, int notifId) {
        if (Build.VERSION.SDK_INT >= 26) {
            createChannel(notificationManager);
        }
        NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(this, getClass().getName());
        notifBuilder
                .setOngoing(true)
                .setSmallIcon(smallIcon())
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), largeIcon()))
                .setColor(getResources().getColor(R.color.colorSmallIcon));
        Notification notification = notifBuilder.build();
        notificationManager.notify(notifId, notification);
        return notifBuilder;
    }

    abstract FileTask startTask(NotificationCompat.Builder notifBuilder,
                                 NotificationManager notificationManager,
                                 int notifId,
                                 PersistableBundle bundle,
                                 Runnable endRunnable,
                                 PendingIntent notifIntent);
    abstract int smallIcon();
    abstract int largeIcon();

    @Override
    public boolean onStopJob(JobParameters params) {
        if (fileTask != null) {
            fileTask.cancel(true);

        }
        return false;
    }

    static abstract class FileTask<Param> extends AsyncTask<Param, Integer, Void> implements TransferListener {
        private NotificationCompat.Builder notifBuilder;
        private NotificationCompat.BigTextStyle notifStyle;
        private NotificationManager notificationManager;
        private int notifId;
        private Runnable endRunnable;
        private PendingIntent notifIntent;
        private String remotePeer;
        private String totalBytes;
        private volatile boolean canceled;

        FileTask(NotificationCompat.Builder notifBuilder,
                 NotificationManager notificationManager,
                 int notifId, Runnable endRunnable,
                 PendingIntent notifIntent) {
            this.notifBuilder = notifBuilder;
            this.notificationManager = notificationManager;
            this.notifId = notifId;
            this.endRunnable = endRunnable;
            this.notifIntent = notifIntent;
            notifStyle = new NotificationCompat.BigTextStyle();

            notifBuilder.addAction(R.drawable.upload_little, "cancel", notifIntent);

        }

        @Override
        protected final void onProgressUpdate(Integer... values) {
            onProgressUpdate(values[0]);
        }

        @Override
        public void onProgressUpdate(int progress) {
            notifBuilder.setProgress(100, progress, false);
            notifStyle.bigText(bytesToString(bytesProcessed()) + "/ " + totalBytes);
            updateNotification();
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            endRunnable.run();
            dispose();
            Log.e("DISPOSE","DISPOSE");
        }

        public void cancel() {
            canceled = true;
            Log.e("CANCELING","CANCELING");
            onCancelled(null);
        }

        @Override
        protected final void onCancelled(Void result) {
            notifBuilder.setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true);

            onCancelled();
            updateNotification();
        }

        @Override
        public final void onConnected(String remoteAddress, int port, String fileName) {
            remotePeer = remoteAddress + ":" + port;
            totalBytes = bytesToString(totalBytes());
            getNotifBuilder()
                    .setProgress(100, 0, false)
                    .setContentText("")
                    .setContentTitle(onConnected(remotePeer, fileName))
                    .setStyle(notifStyle.bigText(""));
            updateNotification();
        }

        @Override
        public abstract void onCancelled();
        abstract String onConnected(String remotePeer, String fileName); //return the title of the notification

        abstract long totalBytes();
        abstract long bytesProcessed();

        private static String bytesToString(long bytes) {
            String units = "kMG";
            long denominator = 1;
            int i = 0;

            while (bytes / (denominator * 1024) > 0 && i < units.length()) {
                denominator *= 1024f;
                i++;
            }
            return String.format(Locale.US, "%.1f %cB", ((float)bytes)/((float)denominator), units.charAt(i));
        }

        NotificationCompat.Builder getNotifBuilder() {
            return notifBuilder;
        }

        void updateNotification() {
            notificationManager.notify(notifId, notifBuilder.build());
        }

        NotificationCompat.Builder finishNotification() {
            notifBuilder.mActions.clear();
            return notifBuilder.setStyle(null)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentText("")
                    .setProgress(0, 0, false);
        }

        void dispose() {
            notifBuilder = null;
            notifStyle = null;
            notificationManager = null;
            endRunnable = null;
            notifIntent = null;
        }
    }

    public class NotificationBroadcastReceiver extends BroadcastReceiver {

        @Override
        public final void onReceive(Context context, Intent intent) {
            if (ACTION_CANCEL.equals(intent.getAction()) && fileTask != null) {
                Log.e("CANCEL","CANCEL");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        fileTask.cancel();
                    }
                }).start();
                Log.e("CANCELED","CANCELED");
            }
        }
    }
}

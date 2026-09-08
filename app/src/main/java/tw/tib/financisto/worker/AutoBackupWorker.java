package tw.tib.financisto.worker;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;
import java.util.Date;

import tw.tib.financisto.ai.AiLog;
import tw.tib.financisto.backup.DatabaseExport;
import tw.tib.financisto.db.DatabaseAdapter;
import tw.tib.financisto.export.Export;
import tw.tib.financisto.service.DailyAutoBackupScheduler;
import tw.tib.financisto.utils.MyPreferences;
import tw.tib.financisto.utils.NotificationUtils;

public class AutoBackupWorker extends Worker {
    public static final String WORK_NAME = "AutoBackuo";
    public static final String SCHEDULE_TIME = "scheduleTime";
    /** 遠端觸發的備份做完要在手機上發通知（每日排程的不發，天天叫就是噪音）。 */
    public static final String NOTIFY_RESULT = "notifyResult";

    private String TAG = "AutoBackupWorker";

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /**
     * 立刻跑一次備份（給遠端觸發用：CREATE_BACKUP intent、通知指令 🧾備份）。
     *
     * <p>刻意走與每日自動備份完全相同的 worker，而不是單獨 export：這樣才會順手帶出
     * AI 解析紀錄（電腦端靠它累積語料，手機只留 1000 筆會被捲掉），失敗也會記進
     * autobackup 狀態，而不是靜默吞掉。跑完會重排每日備份的下一次時間，無害。
     */
    public static void requestImmediateBackup(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AutoBackupWorker.class)
                .setInputData(new Data.Builder()
                        .putLong(SCHEDULE_TIME, System.currentTimeMillis())
                        .putBoolean(NOTIFY_RESULT, true)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueue(request);
    }

    /** 從 SAF Uri 摳出人看的檔名（lastPathSegment 長得像 primary:financisto/2026….backup）。 */
    private static String displayName(Uri uri) {
        String s = uri.getLastPathSegment();
        if (s == null) return uri.toString();
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        return (i >= 0) ? s.substring(i + 1) : s;
    }

    @NonNull
    @Override
    public Result doWork() {
        StringBuilder log = new StringBuilder();
        Context context = getApplicationContext();
        Data args = getInputData();
        long scheduledTime = args.getLong(SCHEDULE_TIME, System.currentTimeMillis());
        boolean notifyResult = args.getBoolean(NOTIFY_RESULT, false);
        DatabaseAdapter db = new DatabaseAdapter(context);
        db.open();

        try {
            long t0 = System.currentTimeMillis();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(scheduledTime);
            Log.e(TAG, "Auto-backup started at " + new Date());
            log.append(String.format("Auto-backup started at %s for %s (%s)\n", t0, scheduledTime, c.getTime()));
            DatabaseExport export = new DatabaseExport(context, db.db(), true);
            Uri backupFileUri = export.export();
            boolean successful = true;
            // 解析紀錄跟著每日備份一起送出去：手機端只留 1000 筆會被捲掉，而電腦端要靠它
            // 累積語料做回歸比對。失敗不影響備份本身——備份是資料，紀錄只是研究素材。
            try {
                AiLog.exportToBackupFolder(context);
            } catch (Exception e) {
                Log.e(TAG, "Unable to export AI log alongside backup", e);
                log.append("Unable to export AI log\n").append(e);
            }
            if (MyPreferences.isDropboxUploadAutoBackups()) {
                try {
                    Export.uploadBackupFileToDropbox(context, backupFileUri);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to upload auto-backup to Dropbox", e);
                    log.append("Unable to upload auto-backup to Dropbox\n").append(e);
                    MyPreferences.notifyAutobackupFailed(e);
                    successful = false;
                }
            }
            if (MyPreferences.isGoogleDriveUploadAutoBackups()) {
                try {
                    Export.uploadBackupFileToGoogleDrive(context, backupFileUri);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to upload auto-backup to Google Drive", e);
                    log.append("Unable to upload auto-backup to Google Drive\n").append(e);
                    MyPreferences.notifyAutobackupFailed(e);
                    successful = false;
                }
            }
            Log.e(TAG, "Auto-backup completed in " + (System.currentTimeMillis() - t0) + "ms");
            log.append("Auto-backup completed in ").append(System.currentTimeMillis() - t0).append("ms");
            if (successful) {
                MyPreferences.notifyAutobackupSucceeded();
            }
            if (notifyResult) {
                NotificationUtils.notifyBackupResult(context, true, displayName(backupFileUri));
            }

        } catch (Exception e) {
            Log.e(TAG, "Auto-backup unsuccessful", e);
            MyPreferences.notifyAutobackupFailed(e);
            if (notifyResult) {
                NotificationUtils.notifyBackupResult(context, false, e.toString());
            }

            return Result.failure();
        }

        DailyAutoBackupScheduler.scheduleNextAutoBackup(context);

        return Result.success();
    }
}

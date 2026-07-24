/*******************************************************************************
 * Copyright (c) 2010 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     Denis Solonenko - initial API and implementation
 ******************************************************************************/
package tw.tib.financisto.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import tw.tib.financisto.service.DailyAutoBackupScheduler;
import tw.tib.financisto.service.FinancistoService;
import tw.tib.financisto.service.NotificationListener;

public class PackageReplaceReceiver extends BroadcastReceiver {
    private static final String TAG = "PackageReplaceReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Received intent " + intent.getAction());
        Log.i(TAG, "reschedule transactions and auto backup");
        requestScheduleAll(context);
        requestScheduleAutoBackup(context);
        // APK 更新後 notification listener 常被系統解綁（權限看起來還在但收不到通知），
        // 主動請系統重綁自癒（2026-07-23 Gary 實機更新後踩到）
        NotificationListener.requestRebindIfGranted(context);
    }

    protected void requestScheduleAll(Context context) {
        Intent serviceIntent = new Intent(FinancistoService.ACTION_SCHEDULE_ALL, null, context, FinancistoService.class);
        FinancistoService.enqueueWork(context, serviceIntent);
    }

    protected void requestScheduleAutoBackup(Context context) {
        DailyAutoBackupScheduler.scheduleNextAutoBackup(context);
    }

}

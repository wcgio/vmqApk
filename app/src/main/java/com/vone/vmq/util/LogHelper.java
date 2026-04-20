package com.vone.vmq.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 2026 旗舰版：日志归档助手
 * 策略：按天滚动归档，保留最近 7 天日志
 */
public class LogHelper {
    private static final String TAG = "VmqLogHelper";
    private static final int RETENTION_DAYS = 7;
    private static final SimpleDateFormat FILE_NAME_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private static final SimpleDateFormat LOG_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);
    
    // 异步写入线程池，防止阻塞主流程
    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    
    /**
     * 写入持久化日志
     */
    public static void writeLog(Context context, String content) {
        logExecutor.execute(() -> {
            try {
                File logDir = new File(context.getFilesDir(), "logs");
                if (!logDir.exists()) logDir.mkdirs();

                // 1. 自动清理过期日志 (7天前)
                cleanOldLogs(logDir);

                // 2. 获取当天文件名
                String fileName = FILE_NAME_FORMAT.format(new Date()) + ".log";
                File logFile = new File(logDir, fileName);

                // 3. 追加写入
                String timePrefix = LOG_TIME_FORMAT.format(new Date());
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
                    bw.write(timePrefix + " | " + content);
                    bw.newLine();
                }
            } catch (IOException e) {
                Log.e(TAG, "Log writing failed: " + e.getMessage());
            }
        });
    }

    /**
     * 清理 7 天前的旧日志
     */
    private static void cleanOldLogs(File logDir) {
        File[] files = logDir.listFiles();
        if (files == null || files.length <= RETENTION_DAYS) return;

        // 按文件名（日期）排序
        Arrays.sort(files);

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -RETENTION_DAYS);
        String threshold = FILE_NAME_FORMAT.format(calendar.getTime());

        for (File file : files) {
            String name = file.getName().replace(".log", "");
            if (name.compareTo(threshold) < 0) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted expired log: " + file.getName());
                }
            }
        }
    }
}
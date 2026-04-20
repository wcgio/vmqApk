package com.vone.vmq;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vone.qrcode.R;
import com.vone.vmq.util.Constant;
import com.google.zxing.activity.CaptureActivity;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity{


    private TextView txthost;
    private TextView txtkey;
    private TextView txtLogs;
    private ScrollView logScroll;
    private LogReceiver logReceiver;

    // UI components from ui.png
    private View viewStatusDot;
    private TextView txtStatusLabel;
    private View layoutStatusPill;

    private boolean isOk = false;
    private static String TAG = "MainActivity";

    public static final String ACTION_ADD_LOG = "com.vone.vmq.ADD_LOG";

    private class LogReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("msg");
            boolean isError = intent.getBooleanExtra("isError", false);
            boolean isBackground = intent.getBooleanExtra("isBackground", false);
            int status = intent.getIntExtra("status", -1);

            // 1. 更新状态灯
            if (status != -1) {
                updateStatusUI(status);
            }

            // 2. 只有非后台日志，或者后台报错日志才显示在 UI 上
            if (!isBackground || isError) {
                addLog(msg, isError);
            }
        }
    }

    private void updateStatusUI(int status) {
        runOnUiThread(() -> {
            if (viewStatusDot == null || txtStatusLabel == null || layoutStatusPill == null) return;
            
            android.graphics.drawable.GradientDrawable pillGd = (android.graphics.drawable.GradientDrawable) layoutStatusPill.getBackground();
            
            switch (status) {
                case 0: // OK
                    pillGd.setColor(getResources().getColor(R.color.ep_success_bg));
                    viewStatusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.ep_success_dot)));
                    txtStatusLabel.setText("连接正常");
                    txtStatusLabel.setTextColor(getResources().getColor(R.color.ep_success_text));
                    break;
                case 1: // Pending
                    pillGd.setColor(getResources().getColor(R.color.ep_warning_bg));
                    viewStatusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.ep_warning_dot)));
                    txtStatusLabel.setText("同步中...");
                    txtStatusLabel.setTextColor(getResources().getColor(R.color.ep_warning_text));
                    break;
                case 2: // Error
                    pillGd.setColor(getResources().getColor(R.color.ep_danger_bg));
                    viewStatusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.ep_danger_dot)));
                    txtStatusLabel.setText("连接异常");
                    txtStatusLabel.setTextColor(getResources().getColor(R.color.ep_danger_text));
                    break;
            }
        });
    }

    private static String host;
    private static String key;

    int id = 0;


    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 消除状态栏割裂感：设置状态栏颜色与背景一致
        getWindow().setStatusBarColor(getResources().getColor(R.color.ui_background));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        setContentView(R.layout.activity_main);

        viewStatusDot = findViewById(R.id.view_status_dot);
        txtStatusLabel = findViewById(R.id.txt_status_label);
        layoutStatusPill = findViewById(R.id.layout_status_pill);

        txthost = (TextView) findViewById(R.id.txt_host);
        txtkey = (TextView) findViewById(R.id.txt_key);
        txtLogs = (TextView) findViewById(R.id.txt_logs);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);

        // 动态获取版本号
        String versionName = "";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        addLog("系统初始化完成", false);

        // 更新 Header 版本号显示
        TextView txtVersionHeader = findViewById(R.id.txt_version_header);
        if (txtVersionHeader != null) {
            txtVersionHeader.setText("v" + versionName);
        }

        //检测通知使用权是否启用
        if (!isNotificationListenersEnabled()) {
            addLog("警告：未授权通知使用权！", true);
            updateStatusUI(2);
            gotoNotificationAccessSetting();
        } else {
            updateStatusUI(0);
        }

        // 强力唤醒逻辑：尝试显式启动服务（针对 Android 16）
        try {
            Intent serviceIntent = new Intent(this, NeNotificationService2.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            addLog("尝试强行拉起监控服务进程...", false);
        } catch (Exception e) {
            addLog("强起服务失败 (系统拦截): " + e.getMessage(), true);
        }

        //读入保存的配置数据并显示
        SharedPreferences read = getSharedPreferences("vone", MODE_PRIVATE);
        host = read.getString("host", "");
        key = read.getString("key", "");

        if (host != null && !host.isEmpty() && !key.isEmpty()){
            txthost.setText(host);
            // 完整显示密钥
            txtkey.setText(key);
            isOk = true;
        }

        // Android 13+ 运行时通知权限申请
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10086);
            }
        }

        // 注册日志广播接收器
        logReceiver = new LogReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, new android.content.IntentFilter(ACTION_ADD_LOG), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, new android.content.IntentFilter(ACTION_ADD_LOG));
        }

        //重启监听服务
        toggleNotificationListenerService(this);
    }

    @Override
    protected void onDestroy() {
        if (logReceiver != null) {
            unregisterReceiver(logReceiver);
        }
        super.onDestroy();
    }


    /**
     * 添加运行日志到主界面
     * @param message 日志内容
     * @param isError 是否为错误日志
     */
    private void addLog(final String message, final boolean isError) {
        runOnUiThread(() -> {
            if (txtLogs == null) return;

            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new Date());
            SpannableStringBuilder builder = new SpannableStringBuilder();
            builder.append("[").append(time).append("] ");

            int start = builder.length();
            builder.append(message).append("\n");

            // 2026 智能着色逻辑
            boolean isSuccess = message.contains("响应") || message.contains("200") || message.contains("成功");

            if (isError) {
                builder.setSpan(new ForegroundColorSpan(Color.parseColor("#ef4444")),
                    start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (isSuccess) {
                builder.setSpan(new ForegroundColorSpan(Color.parseColor("#16a34a")),
                    start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            txtLogs.append(builder);
            
            // 3. 同步写入磁盘归档
            com.vone.vmq.util.LogHelper.writeLog(MainActivity.this, message);

            // 自动滚动到底部
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /**
     * 清空运行日志
     */
    public void doClearLog(View v) {
        if (txtLogs != null) {
            txtLogs.setText("[系统] 日志已清空\n");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * 复制运行日志到剪贴板
     */
    public void doCopyLog(View v) {
        if (txtLogs != null && !TextUtils.isEmpty(txtLogs.getText())) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("VmqLogs", txtLogs.getText());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        }
    }

    //扫码配置
    public void startQrCode(View v) {
        addLog("正在启动扫码...", false);
        // 申请相机权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 申请权限
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, Constant.REQ_PERM_CAMERA);
            return;
        }
        // 申请文件读写权限（部分朋友遇到相册选图需要读写权限的情况，这里一并写一下）
        String storagePermission = Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermission = Manifest.permission.READ_MEDIA_IMAGES;
        }

        if (ActivityCompat.checkSelfPermission(this, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            // 申请权限
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{storagePermission}, Constant.REQ_PERM_EXTERNAL_STORAGE);
            return;
        }
        // 二维码扫码
        Intent intent = new Intent(MainActivity.this, CaptureActivity.class);
        startActivityForResult(intent, Constant.REQ_QR_CODE);
    }


    //手动配置
    public void doInput(View v){
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_manual_input, null);
        final EditText inputServer = dialogView.findViewById(R.id.edit_config_input);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_dialog_confirm);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        final AlertDialog dialog = builder.create();
        
        if (dialog.getWindow() != null) {
            // 设置透明背景，以便显示圆角阴影
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            
            // Android 12+ (API 31+) 毛玻璃效果支持
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                    // 使用反射或直接设置（如果 compileSdk 允许）
                    lp.getClass().getField("blurBehindRadius").set(lp, 20);
                    dialog.getWindow().setAttributes(lp);
                } catch (Exception ignored) {}
            }
            
            // 调整弹窗位置和宽度
            android.view.WindowManager.LayoutParams lpW = dialog.getWindow().getAttributes();
            lpW.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8); // 宽度占 80%
            dialog.getWindow().setAttributes(lpW);
        }

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                dialog.dismiss();
            }
        });

        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onClick(View v1) {
                String scanResult = inputServer.getText().toString();
                if (TextUtils.isEmpty(scanResult)) return;

                String h, k;
                if (scanResult.contains("://")) {
                    int lastSlash = scanResult.lastIndexOf("/");
                    if (lastSlash > 8) {
                        h = scanResult.substring(0, lastSlash);
                        k = scanResult.substring(lastSlash + 1);
                    } else {
                        addLog("输入格式错误，请检查！", true);
                        return;
                    }
                } else {
                    String[] tmp = scanResult.split("/");
                    if (tmp.length < 2) {
                        addLog("格式错误！正确格式：域名/密钥", true);
                        return;
                    }
                    h = tmp[0];
                    k = tmp[tmp.length - 1];
                }

                String t = String.valueOf(new Date().getTime());
                String sign = md5(t + k);

                String baseUrl = h;
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    if (baseUrl.startsWith("192.168.") || baseUrl.startsWith("10.") || baseUrl.startsWith("localhost")) {
                        baseUrl = "http://" + baseUrl;
                        addLog("检测到局域网地址，补全 -> http://", false);
                    } else {
                        baseUrl = "https://" + baseUrl;
                        addLog("检测到域名地址，默认补全 -> https://", false);
                    }
                }
                String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/monitor/heart?t=" + t + "&sign=" + sign;

                addLog("手动配置成功，正在探测: " + baseUrl, false);
                updateStatusUI(1);

                OkHttpClient okHttpClient = new OkHttpClient();
                Request request = new Request.Builder().url(url).method("GET", null).build();
                Call call = okHttpClient.newCall(request);
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        addLog("手动探测失败: " + e.getMessage(), true);
                        updateStatusUI(2);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        addLog("手动探测成功，响应码: " + response.code(), false);
                        updateStatusUI(response.code() == 200 ? 0 : 2);
                        isOk = true;
                        response.close();
                    }
                });

                //展示并保存
                txthost.setText(baseUrl);
                // 完整显示密钥
                txtkey.setText(k);
                host = baseUrl;
                key = k;

                SharedPreferences.Editor editor = getSharedPreferences("vone", MODE_PRIVATE).edit();
                editor.putString("host", host);
                editor.putString("key", key);
                editor.apply();

                toggleNotificationListenerService(MainActivity.this);
                dialog.dismiss();
            }
        });
        dialog.show();
    }


    //检测心跳
    public void doStart(View view) {
        if (!isOk){
            Toast.makeText(MainActivity.this, "请您先配置!", Toast.LENGTH_SHORT).show();
            return;
        }

        String t = String.valueOf(new Date().getTime());
        String sign = md5(t+key);

        String baseUrl = host;
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://" + baseUrl;
        }
        String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/monitor/heart?t=" + t + "&sign=" + sign;

        addLog("发起心跳测试...", false);
        updateStatusUI(1);

        OkHttpClient okHttpClient = new OkHttpClient();
        Request request = new Request.Builder().url(url).method("GET",null).build();
        addLog("发起心跳测试，请求地址: " + url, false);
        
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                addLog("心跳测试连接失败: " + e.getMessage(), true);
                updateStatusUI(2);
                Looper.prepare();
                Looper.loop();
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body = response.body() != null ? response.body().string() : "empty body";
                addLog("心跳测试响应 [" + response.code() + "]: " + body, response.code() != 200);
                updateStatusUI(response.code() == 200 ? 0 : 2);
                Looper.prepare();
                Looper.loop();
                response.close();
            }
        });
    }
    //检测监听
    public void checkPush(View v){
        addLog("正在触发本地测试通知，请观察服务是否捕获...", false);
        Notification mNotification;
        NotificationManager mNotificationManager;
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("1",
                    "Channel1", NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            channel.setShowBadge(true);
            mNotificationManager.createNotificationChannel(channel);

            Notification.Builder builder = new Notification.Builder(this,"1");

            mNotification = builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .setContentTitle("V免签测试推送")
                    .setContentText("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .build();
        }else{
            mNotification = new Notification.Builder(MainActivity.this)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .setContentTitle("V免签测试推送")
                    .setContentText("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .build();
        }

        mNotificationManager.notify(id++, mNotification);
    }

    //各种权限的判断
    private void toggleNotificationListenerService(Context context) {
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }
    public boolean isNotificationListenersEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    protected void gotoNotificationAccessSetting() {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

        } catch (ActivityNotFoundException e) {//普通情况下找不到的时候需要再特殊处理找一次
            try {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ComponentName cn = new ComponentName("com.android.settings", "com.android.settings.Settings$NotificationAccessSettingsActivity");
                intent.setComponent(cn);
                intent.putExtra(":settings:show_fragment", "NotificationAccessSettings");
                startActivity(intent);
                return;
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            Toast.makeText(this, "对不起，您的手机暂不支持", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }



    public static String md5(String string) {
        if (TextUtils.isEmpty(string)) {
            return "";
        }
        MessageDigest md5 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");
            byte[] bytes = md5.digest(string.getBytes());
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                String temp = Integer.toHexString(b & 0xff);
                if (temp.length() == 1) {
                    temp = "0" + temp;
                }
                result.append(temp);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }



    @SuppressLint("SetTextI18n")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        //扫描结果回调
        if (requestCode == Constant.REQ_QR_CODE && resultCode == RESULT_OK) {
            Bundle bundle = data.getExtras();
            String scanResult = bundle.getString(Constant.INTENT_EXTRA_KEY_QR_SCAN);

            if (TextUtils.isEmpty(scanResult)) {
                addLog("扫码结果为空", true);
                return;
            }
            
            scanResult = scanResult.trim();
            addLog("扫码成功，原始内容: " + scanResult, false);

            String h, k;
            if (scanResult.contains("://")) {
                // 更加健壮的 URL 解析逻辑
                try {
                    int lastSlash = scanResult.lastIndexOf("/");
                    // 必须确保斜杠不是协议部分的（http://）
                    if (lastSlash > scanResult.indexOf("://") + 2) {
                        h = scanResult.substring(0, lastSlash);
                        k = scanResult.substring(lastSlash + 1);
                    } else {
                        throw new Exception("URL 格式不完整");
                    }
                } catch (Exception e) {
                    addLog("带协议解析失败，尝试普通格式...", true);
                    String[] tmp = scanResult.split("/");
                    if (tmp.length >= 2) {
                        h = tmp[0];
                        k = tmp[tmp.length - 1];
                    } else {
                        addLog("解析完全失败: " + scanResult, true);
                        return;
                    }
                }
            } else {
                // 处理传统格式 host/key
                String[] tmp = scanResult.split("/");
                if (tmp.length < 2) {
                    addLog("格式错误！期望 域名/密钥", true);
                    return;
                }
                h = tmp[0];
                k = tmp[tmp.length - 1];
            }
            
            h = h.trim();
            k = k.trim();
            addLog("解析结果 -> 地址: " + h + " | 密钥: " + k, false);

            String t = String.valueOf(new Date().getTime());
            String sign = md5(t + k);

            String baseUrl = h;
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                // 智能判断：如果是局域网IP或localhost，默认http；域名默认https
                if (baseUrl.startsWith("192.168.") || baseUrl.startsWith("10.") || baseUrl.startsWith("localhost")) {
                    baseUrl = "http://" + baseUrl;
                    addLog("检测到局域网地址，补全 -> http://", false);
                } else {
                    baseUrl = "https://" + baseUrl;
                    addLog("检测到域名地址，默认补全 -> https://", false);
                }
            }
            
            String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/monitor/heart?t=" + t + "&sign=" + sign;
            addLog("最终探测 URL: " + url, false);
            updateStatusUI(1);

            OkHttpClient okHttpClient = new OkHttpClient();
            Request request = new Request.Builder().url(url).method("GET", null).build();
            Call call = okHttpClient.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    addLog("连接探测失败: " + e.getMessage(), true);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    addLog("探测响应: " + response.code() + " " + response.message(), false);
                    updateStatusUI(response.code() == 200 ? 0 : 2);
                    isOk = true;
                    response.close();
                }
            });

            //展示并保存
            txthost.setText(baseUrl);
            // 完整显示密钥
            txtkey.setText(k);
            host = baseUrl;
            key = k;

            SharedPreferences.Editor editor = getSharedPreferences("vone", MODE_PRIVATE).edit();
            editor.putString("host", host);
            editor.putString("key", key);
            editor.apply();

            toggleNotificationListenerService(MainActivity.this);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case Constant.REQ_PERM_CAMERA:
                // 摄像头权限申请
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 获得授权
                    startQrCode(null);
                } else {
                    // 被禁止授权
                    Toast.makeText(MainActivity.this, "请至权限中心打开本应用的相机访问权限", Toast.LENGTH_LONG).show();
                }
                break;
            case Constant.REQ_PERM_EXTERNAL_STORAGE:
                // 文件读写权限申请
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 获得授权
                    startQrCode(null);
                } else {
                    // 被禁止授权
                    String msg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? "请至权限中心打开本应用的照片和视频权限" : "请至权限中心打开本应用的文件读写权限";
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
                break;
        }
    }

}

package com.miragenotify.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.miragenotify.R;
import com.miragenotify.database.AppDatabase;
import com.miragenotify.model.NotificationLog;
import com.miragenotify.model.NotificationRule;
import com.miragenotify.utils.PreferenceManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core service that intercepts and modifies notifications.
 * It deletes the original notification and posts a modified one through this app.
 */
public class NotificationInterceptorService extends NotificationListenerService {
    
    private static final String TAG = "NotificationInterceptor";
    private static final String FOREGROUND_CHANNEL_ID = "mirage_notify_service";
    private static final String MODIFIED_CHANNEL_ID = "modified_notifications";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    
    private AppDatabase database;
    private ExecutorService executorService;
    private PreferenceManager preferenceManager;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        try {
            database = AppDatabase.getInstance(this);
            executorService = Executors.newSingleThreadExecutor();
            preferenceManager = new PreferenceManager(this);
            
            createNotificationChannels();
            
            Notification notification = createForegroundNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null || sbn.getPackageName().equals(getPackageName())) {
            return;
        }
        
        if (preferenceManager == null || !preferenceManager.isServiceEnabled()) {
            return;
        }
        
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        
        // Prevent infinite loops by checking for our custom flag
        if (notification.extras != null && notification.extras.getBoolean("mirage_modified", false)) {
            return;
        }
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.execute(() -> processNotification(sbn));
        }
    }
    
    private void processNotification(StatusBarNotification sbn) {
        try {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            if (extras == null) extras = new Bundle();
            
            String packageName = sbn.getPackageName();
            
            // Extract separately: Heading (Title), Body (Text), and Sub-text (Sender Info)
            CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence subTextCs = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

            String originalTitle = titleCs != null ? titleCs.toString() : "";
            String originalContent = textCs != null ? textCs.toString() : "";
            // Fix 1: Read sender name from the correct field
            String originalSender = subTextCs != null ? subTextCs.toString()
                    : (titleCs != null ? titleCs.toString() : "");
            
            Log.d(TAG, "Interacting with notification from: " + packageName);
            Log.d(TAG, "Original - Title: " + originalTitle + ", Content: " + originalContent + ", Sender: " + originalSender);

            String appName = getAppName(packageName);
            List<NotificationRule> rules = database.notificationRuleDao().getEnabledRulesForPackage(packageName);
            
            String modifiedTitle = originalTitle;
            String modifiedContent = originalContent;
            String modifiedSender = originalSender;
            boolean wasModified = false;
            long appliedRuleId = 0;
            
            if (rules != null) {
                for (NotificationRule rule : rules) {
                    if (applyRule(rule, originalTitle, originalContent, originalSender)) {
                        wasModified = true;
                        appliedRuleId = rule.getId();
                        
                        Log.d(TAG, "Matching rule found: " + rule.getRuleName());

                        // Apply modifications separately based on user logic
                        if (rule.isModifyTitle()) {
                            modifiedTitle = modifyText(originalTitle, rule, rule.getTitleReplacement());
                        }
                        if (rule.isModifyContent()) {
                            modifiedContent = modifyText(originalContent, rule, rule.getContentReplacement());
                        }
                        if (rule.isModifySender()) {
                            modifiedSender = modifyText(originalSender, rule, rule.getSenderReplacement());
                            // Fix 2: Update the title with the renamed sender
                            if (rule.getModificationType() == NotificationRule.ModificationType.RENAME_SENDER) {
                                modifiedTitle = modifiedSender;
                            }
                        }
                        break; // Stop after first matching rule
                    }
                }
            }
            
            // PUSH TO LOGS: Save to database (this connects to your item_log.xml via LogAdapter)
            logNotification(packageName, appName, originalTitle, originalContent, originalSender,
                    modifiedTitle, modifiedContent, modifiedSender, wasModified, appliedRuleId);
            
            // PUSH TO STATUS BAR: Replace original with modified
            if (wasModified) {
                Log.d(TAG, "Modification applied. New Title: " + modifiedTitle + ", Content: " + modifiedContent);
                // 1. Delete (cancel) the original message
                String key = sbn.getKey();
                if (key != null) {
                    cancelNotification(key);
                }
                
                // 2. Push the modified message through our app
                postModifiedNotification(sbn, modifiedTitle, modifiedContent, modifiedSender);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }
    
    private boolean applyRule(NotificationRule rule, String title, String content, String sender) {
        if (rule == null) return false;
        String searchText = rule.getSearchText();
        if (searchText == null || searchText.isEmpty()) return true;
        
        // Match against any field (case-insensitive)
        String lowerSearch = searchText.toLowerCase();
        return (title != null && title.toLowerCase().contains(lowerSearch)) ||
               (content != null && content.toLowerCase().contains(lowerSearch)) ||
               (sender != null && sender.toLowerCase().contains(lowerSearch));
    }
    
    private String modifyText(String text, NotificationRule rule, String replacementValue) {
        if (text == null) return "";
        if (rule == null) return text;
        
        switch (rule.getModificationType()) {
            case REPLACE_TEXT:
                String search = rule.getSearchText();
                if (search != null && !search.isEmpty()) {
                    // Case-insensitive replace
                    return text.replaceAll("(?i)" + java.util.regex.Pattern.quote(search), 
                            replacementValue != null ? replacementValue : "");
                } else {
                    // If keyword is empty, replace the whole thing
                    return replacementValue != null ? replacementValue : "";
                }
            case MASK_TEXT:
                String searchMask = rule.getSearchText();
                if (searchMask != null && !searchMask.isEmpty()) {
                    String masked = searchMask.replaceAll(".", "*");
                    return text.replaceAll("(?i)" + java.util.regex.Pattern.quote(searchMask), masked);
                } else {
                    // If keyword is empty, mask everything
                    return text.replaceAll(".", "*");
                }
            case RENAME_SENDER:
                // Complete override / override logic
                return replacementValue != null ? replacementValue : text;
            default:
                return text;
        }
    }
    
    private void postModifiedNotification(StatusBarNotification sbn, String title, String content, String sender) {
        try {
            Notification original = sbn.getNotification();
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (notificationManager == null) return;
            
            Bundle extras = original.extras != null ? new Bundle(original.extras) : new Bundle();
            extras.putBoolean("mirage_modified", true);
            // Fix 3: Overwrite the extras bundle before posting the notification
            extras.putCharSequence(Notification.EXTRA_TITLE, title);
            extras.putCharSequence(Notification.EXTRA_TEXT, content);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, MODIFIED_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            
            // Mirror the original app's icon for visual context
            try {
                Drawable appIcon = getPackageManager().getApplicationIcon(sbn.getPackageName());
                builder.setLargeIcon(drawableToBitmap(appIcon));
            } catch (Exception ignored) {}

            builder.setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setPriority(Notification.PRIORITY_MAX) 
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .addExtras(extras);
            
            if (sender != null && !sender.isEmpty()) {
                builder.setSubText(sender);
            }
            
            if (original.contentIntent != null) {
                builder.setContentIntent(original.contentIntent);
            }
            
            String tag = "Mirage_" + sbn.getPackageName();
            notificationManager.notify(tag, sbn.getId(), builder.build());
            
        } catch (Exception e) {
            Log.e(TAG, "Error posting modified notification", e);
        }
    }
    
    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 1;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 1;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;

            NotificationChannel serviceChannel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID, "Mirage Service Status", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(serviceChannel);

            NotificationChannel modifiedChannel = new NotificationChannel(
                    MODIFIED_CHANNEL_ID, "Privacy Modifications", NotificationManager.IMPORTANCE_HIGH);
            modifiedChannel.setDescription("Notifications modified by Mirage Notify for privacy");
            manager.createNotificationChannel(modifiedChannel);
        }
    }
    
    private Notification createForegroundNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, FOREGROUND_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        return builder.setContentTitle("Mirage Notify Active")
                .setContentText("Monitoring notifications for privacy")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void logNotification(String packageName, String appName, String originalTitle, String originalContent, 
                                String originalSender, String modifiedTitle, String modifiedContent, 
                                String modifiedSender, boolean wasModified, long ruleId) {
        try {
            NotificationLog log = new NotificationLog();
            log.setPackageName(packageName);
            log.setAppName(appName);
            log.setOriginalTitle(originalTitle);
            log.setOriginalContent(originalContent);
            log.setOriginalSender(originalSender);
            log.setModifiedTitle(modifiedTitle);
            log.setModifiedContent(modifiedContent);
            log.setModifiedSender(modifiedSender);
            log.setWasModified(wasModified);
            log.setRuleId(ruleId);
            log.setTimestamp(System.currentTimeMillis());
            if (database != null) {
                database.notificationLogDao().insert(log);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error logging notification", e);
        }
    }
    
    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
        if (executorService != null) executorService.shutdown();
    }
}

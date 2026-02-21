package com.miragenotify.ui;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.miragenotify.R;
import com.miragenotify.ui.home.HomeFragment;
import com.miragenotify.ui.logs.LogsFragment;
import com.miragenotify.ui.rules.RulesFragment;
import com.miragenotify.ui.settings.SettingsFragment;
import com.miragenotify.utils.NotificationHelper;

/**
 * Main activity that hosts all fragments
 */
public class MainActivity extends AppCompatActivity {
    
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final String MODIFIED_CHANNEL_ID = "modified_notifications";
    private BottomNavigationView bottomNavigation;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Check for notification access (Listener Service) on startup
        if (!NotificationHelper.isNotificationAccessGranted(this)) {
            NotificationHelper.openNotificationAccessSettings(this);
        }

        // Request POST_NOTIFICATIONS permission for Android 13+
        requestNotificationPermission();
        
        // Setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        // Setup bottom navigation
        bottomNavigation = findViewById(R.id.bottom_navigation);
        bottomNavigation.setOnItemSelectedListener(this::onNavigationItemSelected);
        
        // Load default fragment
        if (savedInstanceState == null) {
            loadFragment(new HomeFragment());
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            } else {
                // Permission already granted, post a test modified notification
                postTestNotification();
            }
        } else {
            // Below Android 13, permission is granted at install time
            postTestNotification();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
                postTestNotification();
            } else {
                Toast.makeText(this, "Notification permission denied. App may not show modified notifications.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void postTestNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;

        // Create channel if needed (though service also does this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel modifiedChannel = new NotificationChannel(
                    MODIFIED_CHANNEL_ID, "Privacy Modifications", NotificationManager.IMPORTANCE_HIGH);
            modifiedChannel.setDescription("Notifications modified by Mirage Notify for privacy");
            notificationManager.createNotificationChannel(modifiedChannel);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, MODIFIED_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Mirage Notify")
                .setContentText("Permission granted! This is how modified notifications will appear.")
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true);

        notificationManager.notify(9999, builder.build());
    }
    
    private boolean onNavigationItemSelected(@NonNull MenuItem item) {
        Fragment fragment = null;
        int itemId = item.getItemId();
        
        if (itemId == R.id.nav_home) {
            fragment = new HomeFragment();
            setTitle(R.string.home_title);
        } else if (itemId == R.id.nav_rules) {
            fragment = new RulesFragment();
            setTitle(R.string.rules_title);
        } else if (itemId == R.id.nav_logs) {
            fragment = new LogsFragment();
            setTitle(R.string.logs_title);
        } else if (itemId == R.id.nav_settings) {
            fragment = new SettingsFragment();
            setTitle(R.string.settings_title);
        }
        
        return fragment != null && loadFragment(fragment);
    }
    
    private boolean loadFragment(Fragment fragment) {
        if (fragment != null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .commit();
            return true;
        }
        return false;
    }
}

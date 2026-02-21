package com.miragenotify.ui.logs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.miragenotify.R;
import com.miragenotify.adapter.LogAdapter;
import com.miragenotify.model.NotificationLog;
import com.miragenotify.viewmodel.LogViewModel;

import java.util.List;

public class LogsFragment extends Fragment {
    
    private LogViewModel viewModel;
    private LogAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyState;
    private TabLayout tabLayout;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        viewModel = new ViewModelProvider(this).get(LogViewModel.class);
        
        recyclerView = view.findViewById(R.id.recycler_logs);
        emptyState = view.findViewById(R.id.empty_state_logs);
        tabLayout = view.findViewById(R.id.tab_layout_logs);
        
        adapter = new LogAdapter(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        
        // Initial setup
        observeLogs(false);
        
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                // Remove previous observers to avoid multiple subscriptions
                viewModel.getAllLogs().removeObservers(getViewLifecycleOwner());
                viewModel.getModifiedLogs().removeObservers(getViewLifecycleOwner());
                
                // Observe based on selected tab (index 1 is "Modified Only")
                observeLogs(tab.getPosition() == 1);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        
        View btnClear = view.findViewById(R.id.btn_clear_logs);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> showClearLogsDialog());
        }
    }

    private void observeLogs(boolean modifiedOnly) {
        if (modifiedOnly) {
            viewModel.getModifiedLogs().observe(getViewLifecycleOwner(), this::updateUI);
        } else {
            viewModel.getAllLogs().observe(getViewLifecycleOwner(), this::updateUI);
        }
    }

    private void updateUI(List<NotificationLog> logs) {
        adapter.setLogs(logs);
        if (logs == null || logs.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void showClearLogsDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_clear_logs_title)
                .setMessage(R.string.dialog_clear_logs_message)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    viewModel.deleteAll();
                    Toast.makeText(requireContext(), R.string.logs_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

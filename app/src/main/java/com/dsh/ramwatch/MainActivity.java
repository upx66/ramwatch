package com.dsh.ramwatch;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AppListAdapter.Listener {

    private static final int SORT_RECENT = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_SIZE = 2;
    private static final String PREF = "ramwatch";
    private static final String KEY_IGNORE_BANNER = "ignore_banner";
    private static final String KEY_NOTIFY = "notify_on";
    private static final String KEY_SYSTEM = "show_system";
    private static final int REQ_NOTIF = 41;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private SharedPreferences prefs;

    private AppListAdapter adapter;
    private ListView list;
    private EditText etSearch;
    private TextView tvUsed, tvFree, tvTotal, tvSwap, tvEmpty;
    private TextView btnSort, btnSystem, btnNotify, btnRefresh, btnGrant, btnIgnore;
    private ProgressBar pbMem;
    private LinearLayout banner;

    private final List<AppEntry> all = new ArrayList<>();
    private int sortMode = SORT_RECENT;
    private boolean showSystem = false;
    private String query = "";
    private boolean loading = false;
    private boolean lastUsageGranted = false;

    private int savedPos = 0;
    private int savedTop = 0;

    private final Runnable memTick = new Runnable() {
        @Override
        public void run() {
            updateMemory();
            ui.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        io = Executors.newSingleThreadExecutor();
        showSystem = prefs.getBoolean(KEY_SYSTEM, false);

        list = findViewById(R.id.list);
        etSearch = findViewById(R.id.etSearch);
        tvUsed = findViewById(R.id.tvUsed);
        tvFree = findViewById(R.id.tvFree);
        tvTotal = findViewById(R.id.tvTotal);
        tvSwap = findViewById(R.id.tvSwap);
        tvEmpty = findViewById(R.id.tvEmpty);
        pbMem = findViewById(R.id.pbMem);
        banner = findViewById(R.id.banner);
        btnSort = findViewById(R.id.btnSort);
        btnSystem = findViewById(R.id.btnSystem);
        btnNotify = findViewById(R.id.btnNotify);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnGrant = findViewById(R.id.btnGrant);
        btnIgnore = findViewById(R.id.btnIgnore);

        adapter = new AppListAdapter(this, this);
        list.setAdapter(adapter);

        btnSort.setOnClickListener(v -> {
            sortMode = (sortMode + 1) % 3;
            applyFilter(true);
            updateChips();
        });
        btnSystem.setOnClickListener(v -> {
            showSystem = !showSystem;
            prefs.edit().putBoolean(KEY_SYSTEM, showSystem).apply();
            applyFilter(true);
            updateChips();
        });
        btnRefresh.setOnClickListener(v -> loadApps());
        btnNotify.setOnClickListener(v -> onNotifyChip());
        btnGrant.setOnClickListener(v -> openUsageAccess());
        btnIgnore.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_IGNORE_BANNER, true).apply();
            banner.setVisibility(View.GONE);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter(false);
            }
        });

        updateChips();
        updateMemory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(memTick);
        refreshBanner();
        boolean granted = hasUsageAccess();
        if (all.isEmpty() || granted != lastUsageGranted) {
            lastUsageGranted = granted;
            loadApps();
        }
        updateChips();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(memTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) io.shutdownNow();
    }

    // ================= 内存 =================

    private void updateMemory() {
        MemoryUtils.Mem m = MemoryUtils.read(this);
        tvUsed.setText(MemoryUtils.size(m.used));
        tvFree.setText(MemoryUtils.size(m.avail));
        tvTotal.setText(MemoryUtils.size(m.total));

        int pct = MemoryUtils.percent(m.used, m.total);
        pbMem.setProgress(pct * 10);
        tvUsed.setTextColor(m.low || pct >= 88
                ? getColor(R.color.warn)
                : getColor(R.color.accent));

        if (m.swapTotal > 0) {
            tvSwap.setText("已用 " + pct + "%　·　占用来自 /proc/meminfo\n虚拟内存（ZRAM）"
                    + MemoryUtils.size(m.swapTotal - m.swapFree) + " / " + MemoryUtils.size(m.swapTotal));
        } else {
            tvSwap.setText("已用 " + pct + "%　·　系统未启用虚拟内存");
        }
        pbMem.setProgressTintList(android.content.res.ColorStateList.valueOf(
                m.low || pct >= 88 ? getColor(R.color.warn) : getColor(R.color.accent)));
    }

    // ================= 授权 =================

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshBanner() {
        boolean ignore = prefs.getBoolean(KEY_IGNORE_BANNER, false);
        banner.setVisibility(!hasUsageAccess() && !ignore ? View.VISIBLE : View.GONE);
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            Toast.makeText(this, "找到「内存管家」并允许", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "打不开设置，请手动到 设置→应用→特殊权限", Toast.LENGTH_LONG).show();
        }
    }

    // ================= 应用列表 =================

    private void loadApps() {
        if (loading) return;
        loading = true;
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText("正在读取应用列表…");

        io.execute(() -> {
            final List<AppEntry> result = buildEntries();
            ui.post(() -> {
                loading = false;
                all.clear();
                all.addAll(result);
                applyFilter(true);
            });
        });
    }

    private List<AppEntry> buildEntries() {
        List<AppEntry> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        boolean usage = hasUsageAccess();
        long now = System.currentTimeMillis();

        Map<String, Long> lastUse = new HashMap<>();
        Map<String, Boolean> foreground = new HashMap<>();
        if (usage) {
            try {
                UsageStatsManager usm =
                        (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                List<UsageStats> stats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, now - 30L * 24 * 3600 * 1000, now);
                if (stats != null) {
                    for (UsageStats s : stats) {
                        Long prev = lastUse.get(s.getPackageName());
                        if (prev == null || s.getLastTimeUsed() > prev) {
                            lastUse.put(s.getPackageName(), s.getLastTimeUsed());
                        }
                    }
                }
                UsageEvents events = usm.queryEvents(now - 6L * 3600 * 1000, now);
                if (events != null) {
                    UsageEvents.Event ev = new UsageEvents.Event();
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev);
                        int t = ev.getEventType();
                        if (t == 1) {          // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                            foreground.put(ev.getPackageName(), Boolean.TRUE);
                        } else if (t == 2) {   // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED
                            foreground.put(ev.getPackageName(), Boolean.FALSE);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Exception e) {
            ris = Collections.emptyList();
        }

        StorageStatsManager ssm = null;
        if (usage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ssm = (StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
        }

        Map<String, AppEntry> uniq = new LinkedHashMap<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String pkg = ai.packageName;
            if (pkg == null || uniq.containsKey(pkg)) continue;
            if (pkg.equals(getPackageName())) continue;

            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            try {
                e.name = String.valueOf(ai.loadLabel(pm));
                e.icon = ai.loadIcon(pm);
            } catch (Exception ex) {
                e.name = pkg;
            }
            Long last = lastUse.get(pkg);
            e.lastUsed = last == null ? 0L : last;
            e.foreground = Boolean.TRUE.equals(foreground.get(pkg));

            if (ssm != null) {
                try {
                    StorageStats st = ssm.queryStatsForPackage(
                            StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
                    e.storage = st.getAppBytes() + st.getDataBytes() + st.getCacheBytes();
                } catch (Exception ex) {
                    e.storage = -1L;
                }
            }
            uniq.put(pkg, e);
        }

        out.addAll(uniq.values());
        return out;
    }

    private void applyFilter(boolean keepScroll) {
        if (keepScroll) rememberScroll();

        List<AppEntry> view = new ArrayList<>();
        for (AppEntry e : all) {
            if (!showSystem && e.system) continue;
            if (!query.isEmpty()) {
                String n = e.name == null ? "" : e.name.toLowerCase(Locale.getDefault());
                if (!n.contains(query) && !e.pkg.toLowerCase(Locale.getDefault()).contains(query)) {
                    continue;
                }
            }
            view.add(e);
        }

        final Collator collator = Collator.getInstance(Locale.CHINA);
        if (sortMode == SORT_NAME) {
            Collections.sort(view, (a, b) -> collator.compare(a.name, b.name));
        } else if (sortMode == SORT_SIZE) {
            Collections.sort(view, (a, b) -> Long.compare(b.storage, a.storage));
        } else {
            Collections.sort(view, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        }

        adapter.setData(view);
        if (view.isEmpty()) {
            if (all.isEmpty()) {
                tvEmpty.setText("没读到应用，点右上「刷新」再试");
            } else if (!query.isEmpty()) {
                tvEmpty.setText("没找到「" + query + "」");
            } else {
                tvEmpty.setText("只列第三方应用。点上面的「仅用户应用」切换成含系统应用");
            }
        }
        tvEmpty.setVisibility(view.isEmpty() ? View.VISIBLE : View.GONE);

        if (keepScroll) restoreScroll();
    }

    private void rememberScroll() {
        if (list.getChildCount() > 0) {
            savedPos = list.getFirstVisiblePosition();
            savedTop = list.getChildAt(0).getTop();
        }
    }

    private void restoreScroll() {
        final int pos = savedPos;
        final int top = savedTop;
        list.post(() -> list.setSelectionFromTop(pos, top));
    }

    private void updateChips() {
        String[] names = {"最近使用", "名称", "空间占用"};
        btnSort.setText("排序：" + names[sortMode]);
        btnSystem.setText(showSystem ? "含系统应用" : "仅用户应用");
        btnSystem.setTextColor(showSystem ? getColor(R.color.accent) : getColor(R.color.text_sub));
        boolean on = prefs.getBoolean(KEY_NOTIFY, false);
        btnNotify.setText(on ? "通知栏：开" : "通知栏：关");
        btnNotify.setTextColor(on ? getColor(R.color.accent) : getColor(R.color.text_sub));
    }

    // ================= 行操作 =================

    @Override
    public void onOpen(AppEntry e) {
        launchApp(e.pkg);
    }

    @Override
    public void onStop(AppEntry e) {
        openDetails(e.pkg);
    }

    @Override
    public void onMore(AppEntry e) {
        final String[] items = {"跳转强行停止", "打开应用", "复制包名"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        openDetails(e.pkg);
                    } else if (which == 1) {
                        launchApp(e.pkg);
                    } else {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("pkg", e.pkg));
                        Toast.makeText(this, "已复制 " + e.pkg, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void openDetails(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "点页面里的「强行停止」结束它", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "跳转失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else {
            openDetails(pkg);
        }
    }

    // ================= 通知栏常驻 =================

    private void onNotifyChip() {
        boolean on = prefs.getBoolean(KEY_NOTIFY, false);
        if (on) {
            stopService(new Intent(this, MemNotifyService.class));
            prefs.edit().putBoolean(KEY_NOTIFY, false).apply();
            updateChips();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        startNotify();
    }

    private void startNotify() {
        Intent i = new Intent(this, MemNotifyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        prefs.edit().putBoolean(KEY_NOTIFY, true).apply();
        updateChips();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIF) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startNotify();
            } else {
                Toast.makeText(this, "没有通知权限，开不了常驻", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

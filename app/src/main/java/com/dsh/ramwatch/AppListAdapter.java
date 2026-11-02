package com.dsh.ramwatch;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class AppListAdapter extends BaseAdapter {

    public interface Listener {
        void onOpen(AppEntry e);

        void onStop(AppEntry e);

        void onMore(AppEntry e);
    }

    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<AppEntry> data = new ArrayList<>();
    private boolean usageGranted = false;

    public void setUsageGranted(boolean granted) {
        this.usageGranted = granted;
    }

    public AppListAdapter(Context ctx, Listener l) {
        this.inflater = LayoutInflater.from(ctx);
        this.listener = l;
    }

    public void setData(List<AppEntry> items) {
        data.clear();
        data.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_app, parent, false);
            h = new ViewHolder();
            h.icon = convertView.findViewById(R.id.ivIcon);
            h.name = convertView.findViewById(R.id.tvName);
            h.sub = convertView.findViewById(R.id.tvSub);
            h.stop = convertView.findViewById(R.id.btnStop);
            h.more = convertView.findViewById(R.id.btnMore);
            convertView.setTag(h);
        } else {
            h = (ViewHolder) convertView.getTag();
        }

        final AppEntry e = data.get(position);
        h.name.setText(e.name);
        h.sub.setText(subtitle(e, usageGranted));
        h.sub.setTextColor(subColor(e));
        if (e.icon != null) {
            h.icon.setImageDrawable(e.icon);
        } else {
            h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        View.OnClickListener open = v -> listener.onStop(e);
        convertView.setOnClickListener(open);
        h.stop.setOnClickListener(v -> listener.onStop(e));
        h.more.setOnClickListener(v -> listener.onMore(e));
        return convertView;
    }

    private int subColor(AppEntry e) {
        return e.foreground ? 0xFF12A150 : 0xFF78828F;
    }

    private static String subtitle(AppEntry e, boolean granted) {
        StringBuilder sb = new StringBuilder();
        if (!granted) {
            sb.append("未开启使用情况访问");
        } else if (e.foreground) {
            sb.append("● 正在前台");
        } else if (e.lastUsed <= 0) {
            sb.append("30 天内没打开过");
        } else {
            sb.append("上次前台 ").append(ago(e.lastUsed));
        }
        if (e.storage >= 0) {
            sb.append(" · 空间 ").append(MemoryUtils.size(e.storage));
        }
        if (e.system) {
            sb.append(" · 系统");
        }
        return sb.toString();
    }

    public static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 0) d = 0;
        long min = d / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hr = min / 60;
        if (hr < 24) return hr + " 小时前";
        long day = hr / 24;
        if (day < 30) return day + " 天前";
        return (day / 30) + " 个月前";
    }

    private static class ViewHolder {
        ImageView icon;
        TextView name;
        TextView sub;
        TextView stop;
        TextView more;
    }
}

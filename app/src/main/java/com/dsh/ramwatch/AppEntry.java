package com.dsh.ramwatch;

import android.graphics.drawable.Drawable;

/** 列表里的一行。 */
public class AppEntry {
    public String pkg;
    public String name;
    public Drawable icon;
    public boolean system;
    /** 最近 30 天内最后一次在前台的时间；0 = 近期没用过 */
    public long lastUsed;
    /** 现在是否在前台 */
    public boolean foreground;
    /** 存储占用（安装包+数据+缓存），-1 = 没权限读 */
    public long storage = -1L;

    public String key() {
        return (name == null ? "" : name.toLowerCase()) + "\u0000" + pkg;
    }
}

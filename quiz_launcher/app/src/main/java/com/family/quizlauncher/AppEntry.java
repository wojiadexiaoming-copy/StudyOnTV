package com.family.quizlauncher;

import android.graphics.drawable.Drawable;

public class AppEntry {

    public final String packageName;
    public final String label;
    public final Drawable icon;
    public final boolean restricted;

    public AppEntry(String packageName, String label, Drawable icon, boolean restricted) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.restricted = restricted;
    }
}

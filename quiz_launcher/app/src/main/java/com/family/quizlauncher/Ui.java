package com.family.quizlauncher;

import android.content.Context;

public class Ui {

    public static int dp(Context context, float value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}

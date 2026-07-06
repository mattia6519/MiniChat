package com.example.minichat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

final class Ui {
    private Ui() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView title(Context context, String text) {
        TextView view = text(context, text, 28, Color.rgb(23, 33, 29));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static TextView subtitle(Context context, String text) {
        return text(context, text, 15, Color.rgb(96, 112, 106));
    }

    static TextView text(Context context, String text, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    static EditText input(Context context, String hint) {
        EditText view = new EditText(context);
        view.setHint(hint);
        view.setTextSize(16);
        view.setSingleLine(true);
        view.setBackgroundResource(R.drawable.bg_input);
        view.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        view.setMinHeight(dp(context, 48));
        return view;
    }

    static Button primaryButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_button_primary);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        return button;
    }

    static ViewGroup.MarginLayoutParams margins(ViewGroup.MarginLayoutParams params, int left, int top, int right, int bottom, Context context) {
        params.setMargins(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
        return params;
    }
}

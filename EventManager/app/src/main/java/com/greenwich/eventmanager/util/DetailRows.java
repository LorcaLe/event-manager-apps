package com.greenwich.eventmanager.util;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.google.android.material.color.MaterialColors;
import com.greenwich.eventmanager.R;

/**
 * Builds "label / value" rows (item_detail_row.xml).
 * Used by both the confirmation screen and the event detail screen.
 */
public final class DetailRows {

    private DetailRows() { }

    /** Adds one row; an empty optional value shows "Not provided" in grey italics. */
    public static void add(ViewGroup container, @StringRes int labelRes, String value) {
        View row = LayoutInflater.from(container.getContext())
                .inflate(R.layout.item_detail_row, container, false);
        TextView label = row.findViewById(R.id.tvLabel);
        TextView valueView = row.findViewById(R.id.tvValue);

        label.setText(labelRes);
        if (TextUtils.isEmpty(value)) {
            valueView.setText(R.string.value_not_provided);
            valueView.setTypeface(valueView.getTypeface(), Typeface.ITALIC);
            valueView.setTextColor(MaterialColors.getColor(valueView,
                    com.google.android.material.R.attr.colorOutline));
        } else {
            valueView.setText(value);
        }
        container.addView(row);
    }
}
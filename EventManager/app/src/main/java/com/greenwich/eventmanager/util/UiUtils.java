package com.greenwich.eventmanager.util;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.greenwich.eventmanager.R;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Small helpers shared by the form and detail screens, so the same code is not
 * repeated in every Activity.
 */
public final class UiUtils {

    private UiUtils() { }

    /** Trimmed text of a field ("" if empty). */
    public static String text(TextInputLayout til) {
        EditText et = til.getEditText();
        return (et == null || et.getText() == null) ? "" : et.getText().toString().trim();
    }

    /** Sets a field's text; drop-down fields are filled without opening their list. */
    public static void setText(TextInputLayout til, String value) {
        EditText et = til.getEditText();
        String v = value == null ? "" : value;
        if (et instanceof AutoCompleteTextView) {
            ((AutoCompleteTextView) et).setText(v, false);
        } else if (et != null) {
            et.setText(v);
        }
    }

    public static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Runs the action after every text change in the field. */
    public static void onTextChanged(TextInputLayout til, Runnable action) {
        EditText et = til.getEditText();
        if (et == null) return;
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { action.run(); }
        });
    }

    /** Opens a 24-hour clock picker and writes the chosen time (HH:mm) into the field. */
    public static void showTimePicker(FragmentActivity activity, TextInputLayout target,
                                      int defaultHour) {
        int hour = defaultHour;
        int minute = 0;
        String current = text(target);
        if (DateUtils.isValidTime(current)) {
            hour = Integer.parseInt(current.substring(0, 2));
            minute = Integer.parseInt(current.substring(3, 5));
        }
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText(R.string.title_pick_time)
                .build();
        picker.addOnPositiveButtonClickListener(v -> setText(target,
                String.format(Locale.US, "%02d:%02d", picker.getHour(), picker.getMinute())));
        picker.show(activity.getSupportFragmentManager(), "time_picker");
    }

    /**
     * Android 15+ draws apps edge-to-edge. Pads the root view so content is not hidden
     * behind the status bar, navigation bar and (optionally) the keyboard.
     */
    public static void applyInsets(View root, boolean includeKeyboard) {
        applyInsets(root, includeKeyboard, null);
    }

    /** Same, and also reports when the on-screen keyboard opens or closes. */
    public static void applyInsets(View root, boolean includeKeyboard,
                                   @Nullable Consumer<Boolean> onKeyboardVisibility) {
        boolean[] lastVisible = {false};
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int types = WindowInsetsCompat.Type.systemBars();
            if (includeKeyboard) types |= WindowInsetsCompat.Type.ime();
            Insets bars = insets.getInsets(types);
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);

            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (onKeyboardVisibility != null && visible != lastVisible[0]) {
                lastVisible[0] = visible;
                onKeyboardVisibility.accept(visible);
            }
            return insets;
        });
    }
}
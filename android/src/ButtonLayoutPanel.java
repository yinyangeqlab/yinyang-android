package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** Edits a local draft for evaluating a button layout in private previews. */
final class ButtonLayoutPanel extends LinearLayout {
    private enum Action {
        PLAY_PAUSE("Play / pause"), NEXT("Next track"), PREVIOUS("Previous track"),
        UP("Volume up"), DOWN("Volume down"), MUTE("Mute / unmute"),
        EQ("Toggle EQ"), NEXT_PRESET("Next EQ preset"), PREVIOUS_PRESET("Previous EQ preset"),
        BATTERY("Show battery"), NONE("No action");

        final String label;
        Action(String label) { this.label = label; }
    }

    private static final class Binding {
        final String key, label;
        final Action suggested;

        Binding(String key, String label, Action suggested) {
            this.key = key;
            this.label = label;
            this.suggested = suggested;
        }
    }

    private static final Binding[] PLUS = {
        new Binding("next.tap", "Next tap", Action.PLAY_PAUSE),
        new Binding("next.double", "Next double-tap", Action.NEXT),
        new Binding("prev.tap", "Prev tap", Action.PREVIOUS),
        new Binding("prev.double", "Prev double-tap", Action.NONE),
        new Binding("volume_up.tap", "Vol + tap", Action.UP),
        new Binding("volume_down.tap", "Vol − tap", Action.DOWN)
    };
    private static final Binding[] ORIGINAL = {
        new Binding("blue_upper.tap", "Blue upper tap", Action.PLAY_PAUSE),
        new Binding("blue_upper.double", "Blue upper double-tap", Action.NEXT),
        new Binding("blue_lower.tap", "Blue lower tap", Action.NONE),
        new Binding("blue_lower.double", "Blue lower double-tap", Action.PREVIOUS),
        new Binding("red_upper.tap", "Red upper tap", Action.UP),
        new Binding("red_lower.tap", "Red lower tap", Action.DOWN)
    };

    private final SharedPreferences draft;
    private final Binding[] bindings;
    private final boolean plus;
    private boolean expanded;
    private Binding choosing;

    ButtonLayoutPanel(Context context, boolean plus) {
        super(context);
        this.plus = plus;
        bindings = plus ? PLUS : ORIGINAL;
        draft = context.getSharedPreferences("button-layout-preview", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setPadding(0, dp(context, 8), 0, dp(context, 8));
        render();
    }

    private Action choice(Binding binding) {
        String saved = draft.getString(binding.key, binding.suggested.name());
        for (Action action : Action.values()) {
            if (action.name().equals(saved)) return action;
        }
        return binding.suggested;
    }

    private void render() { render(null); }

    private void render(Binding keepVisible) {
        Context context = getContext();
        removeAllViews();
        Button toggle = CompanionActions.secondary(context, expanded ? "Close button layout" : "Button layout", () -> {
            expanded = !expanded;
            choosing = null;
            render();
        });
        addView(toggle, -1, -2);
        if (!expanded) return;

        note("Draft saved on this phone. Your Qudelix continues using its firmware assignments.");
        for (Binding binding : bindings) {
            Action selected = choice(binding);
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView gesture = text(context, binding.label, 15, INK, false);
            gesture.setPaddingRelative(0, 0, dp(context, 8), 0);
            row.addView(gesture, new LayoutParams(0, -2, 1));
            Button action = CompanionActions.secondary(context, selected.label, () -> {
                choosing = choosing == binding ? null : binding;
                render();
            });
            action.setContentDescription(binding.label + ", " + selected.label);
            action.setSelected(choosing == binding);
            row.addView(action, new LayoutParams(0, -2, 1));
            addView(row, -1, -2);
            if (binding == keepVisible) action.post(() ->
                    action.requestRectangleOnScreen(new Rect(0,0,action.getWidth(),action.getHeight())));
            if (choosing == binding) addActions(binding, selected);
        }
        note(plus ? "Power, button lock and Settings shortcuts stay on the device."
                : "The blue-button holds stay assigned to power and pairing.");
        addView(CompanionActions.secondary(context, "Reset draft", () -> {
            draft.edit().clear().apply();
            choosing = null;
            render();
        }), -1, -2);
    }

    private void addActions(Binding binding, Action selected) {
        Context context = getContext();
        for (Action action : Action.values()) {
            if (action == Action.BATTERY && !plus) continue;
            RadioButton option = new RadioButton(context);
            option.setText(action.label);
            option.setTextSize(15);
            option.setTextColor(INK);
            option.setButtonTintList(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}}, new int[]{BLUE, MUTED}));
            option.setMinHeight(dp(context, 48));
            option.setChecked(action == selected);
            option.setOnClickListener(v -> {
                draft.edit().putString(binding.key, action.name()).apply();
                choosing = null;
                render(binding);
            });
            addView(option, -1, -2);
        }
    }

    private void note(String message) {
        TextView note = text(getContext(), message, 14, MUTED, false);
        note.setPadding(0, dp(getContext(), 8), 0, dp(getContext(), 8));
        addView(note, -1, -2);
    }
}

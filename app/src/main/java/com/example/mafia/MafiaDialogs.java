package com.example.mafia;

import android.app.Activity;
import android.content.Context;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.mafia.classes.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Единый стиль всех диалоговых окон приложения MAFIA.
 * Тёмно-красная тема со скруглёнными краями, в тон общему стилю.
 *
 * Все диалоги в приложении теперь строятся через этот класс —
 * это гарантирует одинаковый внешний вид и поведение.
 */
public final class MafiaDialogs {

    private MafiaDialogs() {}

    public interface OnAction {
        void onClick();
    }

    public interface OnPickPlayer {
        void onPick(Player player);
    }

    // ─────────────────────────────────────────────────────────────
    // УНИВЕРСАЛЬНЫЕ ДИАЛОГИ
    // ─────────────────────────────────────────────────────────────

    /**
     * Да/Нет диалог в стиле MAFIA.
     * Если negativeText == null — кнопка отмены скрыта.
     */
    public static AlertDialog confirm(@NonNull Activity activity,
                                      @Nullable String title,
                                      @Nullable String message,
                                      @Nullable String positiveText,
                                      @Nullable String negativeText,
                                      @Nullable OnAction onPositive,
                                      @Nullable OnAction onNegative) {

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_mafia_alert, null);

        TextView titleView   = view.findViewById(R.id.mafiaDialogTitle);
        TextView messageView = view.findViewById(R.id.mafiaDialogMessage);
        View divider         = view.findViewById(R.id.mafiaDialogDivider);
        Button positiveBtn   = view.findViewById(R.id.mafiaDialogPositive);
        Button negativeBtn   = view.findViewById(R.id.mafiaDialogNegative);

        boolean hasTitle = title != null && !title.isEmpty();
        boolean hasMessage = message != null && !message.isEmpty();

        titleView.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
        if (hasTitle) titleView.setText(title);

        messageView.setVisibility(hasMessage ? View.VISIBLE : View.GONE);
        if (hasMessage) messageView.setText(message);

        if (!hasTitle) divider.setVisibility(View.GONE);

        positiveBtn.setText(positiveText != null ? positiveText : "ОК");

        if (negativeText == null) {
            negativeBtn.setVisibility(View.GONE);
        } else {
            negativeBtn.setText(negativeText);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.MafiaDialog)
                .setView(view)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        positiveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPositive != null) onPositive.onClick();
        });
        negativeBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (onNegative != null) onNegative.onClick();
        });

        dialog.show();
        return dialog;
    }

    /** Простой alert с одной кнопкой ОК. */
    public static AlertDialog alert(@NonNull Activity activity,
                                    @Nullable String title,
                                    @Nullable String message,
                                    @Nullable String okText,
                                    @Nullable OnAction onOk) {
        return confirm(activity, title, message, okText, null, onOk, null);
    }

    // ─────────────────────────────────────────────────────────────
    // ДИАЛОГИ ВЫБОРА ИГРОКА (карточки с закруглёнными краями)
    // ─────────────────────────────────────────────────────────────

    /**
     * Выбор цели реанимации — карточки мёртвых игроков.
     * Себя (если я мёртв) выделяет оранжевой рамкой.
     */
    public static AlertDialog resurrectTargets(@NonNull Activity activity,
                                               @NonNull String currentUid,
                                               @Nullable String title,
                                               @NonNull List<Player> players,
                                               @NonNull OnPickPlayer onPick) {
        return playerPicker(activity, currentUid, title, players, true, onPick);
    }

    /**
     * Выбор игрока для разоблачения (для реанимированного).
     * Себя исключает из списка.
     */
    public static AlertDialog revealTargets(@NonNull Activity activity,
                                            @NonNull String currentUid,
                                            @Nullable String title,
                                            @NonNull List<Player> players,
                                            @NonNull OnPickPlayer onPick) {
        // Убираем себя из списка — разоблачать себя нельзя
        List<Player> filtered = new ArrayList<>();
        for (Player p : players) {
            if (p.getId() == null || !p.getId().equals(currentUid)) filtered.add(p);
        }
        return playerPicker(activity, currentUid, title, filtered, false, onPick);
    }

    /**
     * Универсальный список игроков в виде карточек.
     */
    private static AlertDialog playerPicker(@NonNull final Activity activity,
                                            @NonNull final String currentUid,
                                            @Nullable String title,
                                            @NonNull List<Player> players,
                                            boolean markSelf,
                                            @NonNull final OnPickPlayer onPick) {

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_mafia_alert, null);
        TextView titleView   = view.findViewById(R.id.mafiaDialogTitle);
        TextView messageView = view.findViewById(R.id.mafiaDialogMessage);
        View divider         = view.findViewById(R.id.mafiaDialogDivider);
        FrameLayout slot     = view.findViewById(R.id.mafiaDialogContentSlot);
        Button positiveBtn   = view.findViewById(R.id.mafiaDialogPositive);
        Button negativeBtn   = view.findViewById(R.id.mafiaDialogNegative);

        boolean hasTitle = title != null && !title.isEmpty();
        titleView.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
        if (hasTitle) titleView.setText(title);
        if (!hasTitle) divider.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        positiveBtn.setVisibility(View.GONE);
        negativeBtn.setText("ОТМЕНА");

        // ListView в слоте контента
        final ListView listView = new ListView(activity);
        listView.setDividerHeight(0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        listView.setLayoutParams(lp);
        slot.addView(listView);

        final List<Player> items = new ArrayList<>(players);
        final boolean fMarkSelf = markSelf;

        ArrayAdapter<Player> adapter = new ArrayAdapter<Player>(activity, 0, items) {
            @NonNull @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(activity)
                            .inflate(R.layout.item_resurrect_player, parent, false);
                }
                Player p = getItem(position);
                if (p == null) return convertView;

                ImageView avatar = convertView.findViewById(R.id.resurrectPlayerAvatar);
                TextView name    = convertView.findViewById(R.id.resurrectPlayerName);
                TextView hint    = convertView.findViewById(R.id.resurrectPlayerHint);
                TextView icon    = convertView.findViewById(R.id.resurrectPlayerIcon);

                String displayName = (p.getName() != null && !p.getName().isEmpty())
                        ? p.getName() : "Игрок";
                boolean isSelf = p.getId() != null && p.getId().equals(currentUid);

                // Аватар
                loadAvatar(activity, p.getPhotoUrl(), avatar);

                if (fMarkSelf && isSelf) {
                    name.setText("🔁 " + displayName);
                    hint.setText("вернуть себя в игру");
                    icon.setText("🔁");
                    convertView.setBackgroundResource(R.drawable.bg_resurrect_card_self);
                } else {
                    name.setText("💀 " + displayName);
                    hint.setText(fMarkSelf ? "вернуть в игру" : "назвать мафией");
                    icon.setText("→");
                    convertView.setBackgroundResource(R.drawable.bg_resurrect_card);
                }
                return convertView;
            }
        };
        listView.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.MafiaDialog)
                .setView(view)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        listView.setOnItemClickListener((parent, v, position, id) -> {
            dialog.dismiss();
            onPick.onPick(items.get(position));
        });
        negativeBtn.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        return dialog;
    }

    // ─────────────────────────────────────────────────────────────
    // УТИЛИТЫ
    // ─────────────────────────────────────────────────────────────

    private static void loadAvatar(@NonNull Context ctx,
                                   @Nullable String photoUrl,
                                   @NonNull ImageView target) {
        if (photoUrl != null && !photoUrl.isEmpty() && photoUrl.length() > 64) {
            try {
                String b64 = photoUrl;
                int comma = b64.indexOf(',');
                if (comma >= 0 && comma < 100) b64 = b64.substring(comma + 1);
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                Glide.with(ctx).load(bytes)
                        .transform(new CircleCrop())
                        .placeholder(R.drawable.ic_player_avatar1)
                        .into(target);
                return;
            } catch (Exception ignored) {}
        }
        target.setImageResource(R.drawable.ic_player_avatar1);
    }
}
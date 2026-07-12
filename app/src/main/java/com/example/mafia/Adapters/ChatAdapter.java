package com.example.mafia.Adapters;

import android.graphics.Color;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.mafia.R;
import com.example.mafia.classes.ChatMessage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ChatAdapter с поддержкой ghost-чата (мёртвые <-> мёртвые) и каналов (public/mafia).
 *
 * Правила видимости:
 *  - Живой игрок: видит ТОЛЬКО сообщения с isGhost==false
 *  - Мёртвый игрок: видит ВСЕ сообщения, но ghost-сообщения выделены особо
 *
 * Фильтр каналов:
 *  - filterChannel == null → показывать все (дневной чат)
 *  - filterChannel == "public" или "mafia" → показывать только сообщения этого канала
 *
 * Визуальное разделение:
 *  - Обычные сообщения — стандартный тёмный пузырь (как раньше)
 *  - Ghost-сообщения своих (isMine+isGhost) — полупрозрачный фиолетовый пузырь справа
 *  - Ghost-сообщения других (isGhost, !isMine) — фиолетовый пузырь слева + 💀 перед именем
 *  - Mafia-сообщения (channel=mafia) — красноватый пузырь
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final String myUid;
    private boolean viewerIsAlive = true;   // статус текущего игрока

    private final List<ChatMessage> allMessages = new ArrayList<>();   // все входящие
    private final List<ChatMessage> visibleMessages = new ArrayList<>(); // то, что показываем

    private String filterChannel = null; // null = show all, "public" or "mafia" = filter

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    // Цвета ghost-чата
    private static final int GHOST_BG_MINE  = 0xCC4A0080;   // фиолетовый, свои
    private static final int GHOST_BG_OTHER = 0xCC2D0060;   // тёмно-фиолетовый, чужие
    private static final int GHOST_TEXT     = 0xFFD9A0FF;   // сиреневый текст
    private static final int GHOST_NAME     = 0xFFB060FF;   // фиолетовый для имени

    // Цвета mafia-чата
    private static final int MAFIA_BG_MINE  = 0xCC5C1010;   // тёмно-красный, свои
    private static final int MAFIA_BG_OTHER = 0xCC3A0808;   // ещё темнее, чужие
    private static final int MAFIA_TEXT     = 0xFFFFB3B3;   // светло-красный текст
    private static final int MAFIA_NAME     = 0xFFFF6666;   // красный для имени

    public ChatAdapter(String myUid) {
        this.myUid = myUid;
    }

    /** Установить фильтр по каналу. null = показать все. */
    public void setChannelFilter(String channel) {
        this.filterChannel = channel;
        rebuildVisible();
    }

    /** Вызывать при изменении статуса живой/мёртвый. */
    public void setViewerAlive(boolean alive) {
        this.viewerIsAlive = alive;
        rebuildVisible();
    }

    public void addMessage(ChatMessage msg) {
        allMessages.add(msg);
        if (shouldShow(msg)) {
            visibleMessages.add(msg);
            notifyItemInserted(visibleMessages.size() - 1);
        }
    }

    public void setMessages(List<ChatMessage> newMessages) {
        allMessages.clear();
        allMessages.addAll(newMessages);
        rebuildVisible();
    }

    public void clear() {
        allMessages.clear();
        visibleMessages.clear();
        notifyDataSetChanged();
    }

    private void rebuildVisible() {
        visibleMessages.clear();
        for (ChatMessage m : allMessages) {
            if (shouldShow(m)) visibleMessages.add(m);
        }
        notifyDataSetChanged();
    }

    /** Живой видит только живые сообщения. Мёртвый — все. + фильтр канала. */
    private boolean shouldShow(ChatMessage msg) {
        // Фильтр по каналу
        if (filterChannel != null) {
            String ch = msg.getChannel();
            if (ch == null || !ch.equals(filterChannel)) return false;
        }
        if (viewerIsAlive) {
            return !msg.isGhost();          // живые не видят ghost
        }
        return true;                        // мёртвые видят всё
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new ChatViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder h, int position) {
        h.bind(visibleMessages.get(position));
    }

    @Override
    public int getItemCount() { return visibleMessages.size(); }

    class ChatViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout bubbleContainer;
        private final LinearLayout bubble;
        private final ImageView avatar;
        private final TextView nameText;
        private final TextView messageText;
        private final TextView timeText;

        ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            bubbleContainer = itemView.findViewById(R.id.bubbleContainer);
            bubble          = itemView.findViewById(R.id.bubble);
            avatar          = itemView.findViewById(R.id.chatAvatar);
            nameText        = itemView.findViewById(R.id.chatName);
            messageText     = itemView.findViewById(R.id.chatMessage);
            timeText        = itemView.findViewById(R.id.chatTime);
        }

        void bind(ChatMessage msg) {
            boolean isMine  = myUid.equals(msg.getUid());
            boolean isGhost = msg.isGhost();
            boolean isMafia = "mafia".equals(msg.getChannel());

            messageText.setText(msg.getText());
            timeText.setText(TIME_FMT.format(new Date(msg.getTimestamp())));

            if (isGhost) {
                bindGhost(msg, isMine);
            } else if (isMafia) {
                bindMafia(msg, isMine);
            } else {
                bindNormal(msg, isMine);
            }
        }

        // ── Обычное сообщение (как раньше) ───────────────────────────────
        private void bindNormal(ChatMessage msg, boolean isMine) {
            if (isMine) {
                bubbleContainer.setGravity(Gravity.END);
                nameText.setVisibility(View.GONE);
                avatar.setVisibility(View.GONE);
                bubble.setBackgroundResource(R.drawable.bg_chat_bubble_mine);
                messageText.setTextColor(0xFFFFFFFF);
                timeText.setTextColor(0xAAFFFFFF);
            } else {
                bubbleContainer.setGravity(Gravity.START);
                nameText.setVisibility(View.VISIBLE);
                nameText.setText(msg.getName());
                nameText.setTextColor(0xFF8B0000);
                avatar.setVisibility(View.VISIBLE);
                bubble.setBackgroundResource(R.drawable.bg_chat_bubble_other);
                messageText.setTextColor(0xFFDDDDDD);
                timeText.setTextColor(0xFF777777);
                loadAvatar(msg.getPhotoBase64());
            }
        }

        // ── Mafia сообщение (ночной чат мафии) ───────────────────────────
        private void bindMafia(ChatMessage msg, boolean isMine) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(18f);
            bg.setStroke(2, 0xFFB71C1C);

            if (isMine) {
                bubbleContainer.setGravity(Gravity.END);
                nameText.setVisibility(View.GONE);
                avatar.setVisibility(View.GONE);
                bg.setColor(MAFIA_BG_MINE);
                bubble.setBackground(bg);
                messageText.setTextColor(MAFIA_TEXT);
                timeText.setTextColor(0xAAB71C1C);
            } else {
                bubbleContainer.setGravity(Gravity.START);
                nameText.setVisibility(View.VISIBLE);
                nameText.setText("🔫 " + msg.getName());
                nameText.setTextColor(MAFIA_NAME);
                avatar.setVisibility(View.VISIBLE);
                bg.setColor(MAFIA_BG_OTHER);
                bubble.setBackground(bg);
                messageText.setTextColor(MAFIA_TEXT);
                timeText.setTextColor(0xAAB71C1C);
                loadAvatar(msg.getPhotoBase64());
                avatar.setAlpha(0.85f);
            }
        }

        // ── Ghost сообщение (мёртвый чат) ────────────────────────────────
        private void bindGhost(ChatMessage msg, boolean isMine) {
            // Полупрозрачный фиолетовый фон пузыря
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(18f);
            bg.setStroke(2, 0xFF9C27B0);

            if (isMine) {
                bubbleContainer.setGravity(Gravity.END);
                nameText.setVisibility(View.GONE);
                avatar.setVisibility(View.GONE);
                bg.setColor(GHOST_BG_MINE);
                bubble.setBackground(bg);
                messageText.setTextColor(GHOST_TEXT);
                timeText.setTextColor(0xAA9C27B0);
            } else {
                bubbleContainer.setGravity(Gravity.START);
                nameText.setVisibility(View.VISIBLE);
                nameText.setText("💀 " + msg.getName());
                nameText.setTextColor(GHOST_NAME);
                avatar.setVisibility(View.VISIBLE);
                bg.setColor(GHOST_BG_OTHER);
                bubble.setBackground(bg);
                messageText.setTextColor(GHOST_TEXT);
                timeText.setTextColor(0xAA9C27B0);
                loadAvatar(msg.getPhotoBase64());
                // Полупрозрачность аватарки для мёртвых
                avatar.setAlpha(0.7f);
            }
        }

        private void loadAvatar(String photoBase64) {
            avatar.setAlpha(1f);
            if (photoBase64 != null && !photoBase64.isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(photoBase64, Base64.DEFAULT);
                    avatar.clearColorFilter();
                    Glide.with(itemView.getContext())
                            .load(bytes)
                            .transform(new CircleCrop())
                            .placeholder(R.drawable.ic_player_avatar1)
                            .into(avatar);
                } catch (Exception e) {
                    avatar.setImageResource(R.drawable.ic_player_avatar1);
                }
            } else {
                avatar.setImageResource(R.drawable.ic_player_avatar1);
            }
        }
    }
}
package com.example.mafia.Adapters;

import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final String myUid;
    private final List<ChatMessage> messages = new ArrayList<>();
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public ChatAdapter(String myUid) {
        this.myUid = myUid;
    }

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        notifyDataSetChanged();
    }

    public void clear() {
        messages.clear();
        notifyDataSetChanged();
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
        h.bind(messages.get(position));
    }

    @Override
    public int getItemCount() { return messages.size(); }

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
            boolean isMine = myUid.equals(msg.getUid());

            messageText.setText(msg.getText());
            timeText.setText(TIME_FMT.format(new Date(msg.getTimestamp())));

            if (isMine) {
                bubbleContainer.setGravity(Gravity.END);
                nameText.setVisibility(View.GONE);
                avatar.setVisibility(View.GONE);
                bubble.setBackgroundResource(R.drawable.bg_chat_bubble_mine);
                messageText.setTextColor(0xFFFFFFFF);
            } else {
                bubbleContainer.setGravity(Gravity.START);
                nameText.setVisibility(View.VISIBLE);
                nameText.setText(msg.getName());
                avatar.setVisibility(View.VISIBLE);
                bubble.setBackgroundResource(R.drawable.bg_chat_bubble_other);
                messageText.setTextColor(0xFFDDDDDD);

                String photo = msg.getPhotoBase64();
                if (photo != null && !photo.isEmpty()) {
                    try {
                        byte[] bytes = Base64.decode(photo, Base64.DEFAULT);
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
}
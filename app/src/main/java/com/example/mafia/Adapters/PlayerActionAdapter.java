package com.example.mafia.Adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.mafia.R;
import com.example.mafia.classes.Player;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerActionAdapter extends RecyclerView.Adapter<PlayerActionAdapter.PlayerViewHolder> {

    private List<Player> players = new ArrayList<>();
    private final String currentUserId;
    private OnPlayerClickListener listener;
    private boolean interactionEnabled = true;

    private Map<String, Integer> voteCounts = new HashMap<>();
    private String selectedPlayerId = null;

    public interface OnPlayerClickListener {
        void onPlayerClick(Player player);
    }

    public PlayerActionAdapter(String currentUserId) {
        this.currentUserId = currentUserId;
    }

    public void setPlayers(List<Player> players) {
        this.players = players != null ? players : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setVoteCounts(Map<String, Integer> counts) {
        this.voteCounts = counts != null ? counts : new HashMap<>();
        notifyDataSetChanged();
    }

    public void setSelectedPlayer(String playerId) {
        this.selectedPlayerId = playerId;
        notifyDataSetChanged();
    }

    public void setInteractionEnabled(boolean enabled) {
        this.interactionEnabled = enabled;
        notifyDataSetChanged();
    }

    public void setOnPlayerClickListener(OnPlayerClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public PlayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_player_action, parent, false);
        return new PlayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlayerViewHolder holder, int position) {
        holder.bind(players.get(position));
    }

    @Override
    public int getItemCount() {
        return players.size();
    }

    class PlayerViewHolder extends RecyclerView.ViewHolder {
        final CardView playerCard;
        final ImageView playerAvatar;
        final TextView playerNameText;
        final TextView playerRoleText;

        PlayerViewHolder(@NonNull View itemView) {
            super(itemView);
            playerCard       = itemView.findViewById(R.id.playerCard);
            playerAvatar     = itemView.findViewById(R.id.playerAvatar);
            playerNameText   = itemView.findViewById(R.id.playerNameText);
            playerRoleText   = itemView.findViewById(R.id.playerRoleText);
        }

        void bind(Player player) {
            playerNameText.setText(player.getName());

            // Загружаем фото профиля если есть, иначе дефолтная иконка
            String photoBase64 = player.getPhotoUrl();
            if (photoBase64 != null && !photoBase64.isEmpty()) {
                try {
                    byte[] photoBytes = android.util.Base64.decode(photoBase64, android.util.Base64.DEFAULT);
                    playerAvatar.clearColorFilter();
                    Glide.with(itemView.getContext())
                            .load(photoBytes)
                            .transform(new CircleCrop())
                            .placeholder(R.drawable.ic_player_avatar1)
                            .error(R.drawable.ic_player_avatar1)
                            .into(playerAvatar);
                } catch (Exception e) {
                    playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                    playerAvatar.setColorFilter(
                            androidx.core.content.ContextCompat.getColor(
                                    itemView.getContext(), R.color.icon_tint));
                }
            } else {
                playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                playerAvatar.setColorFilter(
                        androidx.core.content.ContextCompat.getColor(
                                itemView.getContext(), R.color.icon_tint));
            }

            int votes = voteCounts.getOrDefault(player.getId(), 0);
            boolean isSelected = player.getId().equals(selectedPlayerId);
            boolean isMe = player.getId().equals(currentUserId);
            // ИСПРАВЛЕНО: нельзя голосовать за себя и за мёртвых
            boolean canClick = interactionEnabled && !isMe && player.isAlive();

            // Цвет карточки
            if (votes > 0) {
                int red   = Math.min(255, 60 + votes * 55);
                int green = Math.max(0,   20 - votes * 5);
                int blue  = Math.max(0,   20 - votes * 5);
                playerCard.setCardBackgroundColor(Color.rgb(red, green, blue));
            } else if (isSelected) {
                playerCard.setCardBackgroundColor(Color.rgb(40, 40, 90));
            } else if (isMe) {
                playerCard.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.getContext(), R.color.selected_player));
            } else {
                playerCard.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.getContext(), R.color.card_background));
            }

            // Счётчик голосов
            if (votes > 0) {
                playerRoleText.setText("🗳 " + votes);
                playerRoleText.setVisibility(View.VISIBLE);
                playerRoleText.setTextColor(Color.WHITE);
            } else {
                playerRoleText.setVisibility(View.GONE);
            }

            // Прозрачность: себя и недоступных затемняем
            itemView.setAlpha((isMe || !interactionEnabled || !player.isAlive()) ? 0.4f : 1.0f);

            // ИСПРАВЛЕНО: один вызов listener, без дублирования
            itemView.setOnClickListener(v -> {
                if (listener != null && canClick) {
                    listener.onPlayerClick(player);
                }
            });
        }
    }
}
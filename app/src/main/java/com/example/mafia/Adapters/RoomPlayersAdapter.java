package com.example.mafia.Adapters;

import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
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

import java.util.List;

public class RoomPlayersAdapter extends RecyclerView.Adapter<RoomPlayersAdapter.PlayerViewHolder> {

    private List<Player> players;
    private String currentUserId;
    private OnPlayerReadyClickListener listener;

    public interface OnPlayerReadyClickListener {
        void onPlayerReadyClick(Player player);
    }

    public RoomPlayersAdapter(String currentUserId) {
        this.currentUserId = currentUserId;
    }

    public void setPlayers(List<Player> players) {
        this.players = players;
        notifyDataSetChanged();
    }

    public void setOnPlayerReadyClickListener(OnPlayerReadyClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public PlayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_room_player, parent, false);
        return new PlayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlayerViewHolder holder, int position) {
        holder.bind(players.get(position));
    }

    @Override
    public int getItemCount() {
        return players != null ? players.size() : 0;
    }

    class PlayerViewHolder extends RecyclerView.ViewHolder {
        CardView playerCard;
        ImageView playerCardBgImage;   // фото-фон карточки (Premium)
        ImageView playerAvatar;
        TextView playerNameText;
        ImageView hostStar;
        ImageView notReadyIcon;
        ImageView readyIcon;
        TextView premiumBadgeText;

        public PlayerViewHolder(@NonNull View itemView) {
            super(itemView);
            playerCard = itemView.findViewById(R.id.playerCard);
            playerCardBgImage = itemView.findViewById(R.id.playerCardBgImage);
            playerAvatar = itemView.findViewById(R.id.playerAvatar);
            playerNameText = itemView.findViewById(R.id.playerNameText);
            hostStar = itemView.findViewById(R.id.hostStar);
            notReadyIcon = itemView.findViewById(R.id.notReadyIcon);
            readyIcon = itemView.findViewById(R.id.readyIcon);
            premiumBadgeText = itemView.findViewById(R.id.premiumBadgeText);
        }

        public void bind(Player player) {
            // ── Имя + цвет ника (Premium) ──────────────────────────────────
            playerNameText.setText(player.getName());
            if (player.isPremium()
                    && player.getNicknameColor() != null
                    && !player.getNicknameColor().isEmpty()) {
                try {
                    playerNameText.setTextColor(Color.parseColor(player.getNicknameColor()));
                } catch (Exception e) {
                    playerNameText.setTextColor(Color.WHITE);
                }
            } else {
                playerNameText.setTextColor(Color.WHITE);
            }

            // ── Фото аватарки ────────────────────────────────────────────────
            String photoBase64 = player.getPhotoUrl();
            if (photoBase64 != null && !photoBase64.isEmpty()) {
                try {
                    byte[] bytes = android.util.Base64.decode(photoBase64, android.util.Base64.DEFAULT);
                    playerAvatar.clearColorFilter();
                    Glide.with(itemView.getContext())
                            .load(bytes)
                            .transform(new CircleCrop())
                            .placeholder(R.drawable.ic_player_avatar1)
                            .error(R.drawable.ic_player_avatar1)
                            .into(playerAvatar);
                } catch (Exception e) {
                    playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                    playerAvatar.setColorFilter(ContextCompat.getColor(
                            itemView.getContext(), R.color.icon_tint));
                }
            } else {
                playerAvatar.setImageResource(R.drawable.ic_player_avatar1);
                playerAvatar.setColorFilter(ContextCompat.getColor(
                        itemView.getContext(), R.color.icon_tint));
            }

            // ── Бейдж: Premium (произвольный эмодзи) или хост-звезда ──────────
            // Без чёрной подложки — эмодзи/звезда "налезают" на фото за счёт
            // отрицательных отступов, заданных прямо в layout.
            if (premiumBadgeText != null) {
                if (player.isPremium() && player.getAvatarBadge() != null
                        && !player.getAvatarBadge().isEmpty()) {
                    premiumBadgeText.setText(normalizeBadge(player.getAvatarBadge()));
                    premiumBadgeText.setVisibility(View.VISIBLE);
                    if (hostStar != null) hostStar.setVisibility(View.GONE);
                } else if (player.isHost()) {
                    premiumBadgeText.setVisibility(View.GONE);
                    if (hostStar != null) hostStar.setVisibility(View.VISIBLE);
                } else {
                    premiumBadgeText.setVisibility(View.GONE);
                    if (hostStar != null) hostStar.setVisibility(View.GONE);
                }
            } else if (hostStar != null) {
                hostStar.setVisibility(player.isHost() ? View.VISIBLE : View.GONE);
            }

            // ── Рамка карточки (Premium цвет или стандартная) ──────────────────
            if (player.isPremium()
                    && player.getCardBorderColor() != null
                    && !player.getCardBorderColor().isEmpty()) {
                try {
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(Color.TRANSPARENT);
                    gd.setCornerRadius(24f);
                    gd.setStroke(5, Color.parseColor(player.getCardBorderColor()));
                    playerCard.setForeground(gd);
                } catch (Exception e) {
                    playerCard.setForeground(ContextCompat.getDrawable(
                            itemView.getContext(), R.drawable.player_card_border));
                }
            } else {
                playerCard.setForeground(ContextCompat.getDrawable(
                        itemView.getContext(), R.drawable.player_card_border));
            }

            // ── Фон карточки (Premium: цвет или чёрно-белое фото) ──────────────
            // Изображение растянуто ровно на весь FrameLayout карточки
            // (см. item_room_player.xml — match_parent + centerCrop), поэтому
            // не сдвигается и не выходит за границы. Прозрачность regулируется
            // через setAlpha по значению cardBgOpacity (0..100).
            String cardBg = player.isPremium() ? player.getCardBackground() : null;
            if (cardBg != null && cardBg.startsWith("img:")) {
                if (playerCardBgImage != null) {
                    try {
                        byte[] bytes = android.util.Base64.decode(
                                cardBg.substring(4), android.util.Base64.DEFAULT);
                        playerCardBgImage.setVisibility(View.VISIBLE);
                        playerCardBgImage.setColorFilter(grayscaleFilter());
                        playerCardBgImage.setAlpha(player.getCardBgOpacity() / 100f);
                        Glide.with(itemView.getContext()).load(bytes).centerCrop()
                                .into(playerCardBgImage);
                        playerCard.setCardBackgroundColor(Color.TRANSPARENT);
                    } catch (Exception e) {
                        playerCardBgImage.setVisibility(View.GONE);
                        playerCard.setCardBackgroundColor(Color.parseColor("#000000"));
                    }
                }
            } else if (cardBg != null && cardBg.startsWith("#")) {
                if (playerCardBgImage != null) playerCardBgImage.setVisibility(View.GONE);
                try {
                    playerCard.setCardBackgroundColor(Color.parseColor(cardBg));
                } catch (Exception e) {
                    playerCard.setCardBackgroundColor(Color.parseColor("#000000"));
                }
            } else {
                if (playerCardBgImage != null) playerCardBgImage.setVisibility(View.GONE);
                playerCard.setCardBackgroundColor(ContextCompat.getColor(
                        itemView.getContext(), android.R.color.black));
            }

            // ── Готовность ───────────────────────────────────────────────────
            if (notReadyIcon != null)
                notReadyIcon.setVisibility(player.isReady() ? View.GONE : View.VISIBLE);
            if (readyIcon != null)
                readyIcon.setVisibility(player.isReady() ? View.VISIBLE : View.GONE);

            itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.getContext(), android.R.color.transparent));

            itemView.setOnClickListener(v -> {
                if (listener != null && player.getId().equals(currentUserId)) {
                    listener.onPlayerReadyClick(player);
                }
            });
        }

        /**
         * Ч/б фильтр применяется на клиенте как подстраховка — на случай если
         * в базе осталось старое фото, сохранённое до перехода на серверную
         * grayscale-конвертацию (см. PremiumCustomizeActivity.toGrayscale()).
         */
        private ColorMatrixColorFilter grayscaleFilter() {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            return new ColorMatrixColorFilter(matrix);
        }

        /**
         * Поддержка старых значений (до перехода на свободный ввод эмодзи).
         */
        private String normalizeBadge(String badge) {
            if (badge == null || badge.isEmpty()) return "👑";
            switch (badge) {
                case "crown":
                    return "👑";
                case "diamond":
                    return "💎";
                case "fire":
                    return "🔥";
                case "skull":
                    return "💀";
                case "moon":
                    return "🌙";
                default:
                    return badge;
            }
        }
    }
}
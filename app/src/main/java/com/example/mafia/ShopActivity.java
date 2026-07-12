package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

/**
 * Магазин плюшек.
 * Открывается из LobbyActivity или ProfileActivity.
 */
public class ShopActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private String uid;
    private ListenerRegistration userListener;

    private TextView balanceText;

    // Отображение количества плюшек у игрока
    private TextView shieldCountText;
    private TextView selfhealCountText;
    private TextView invisibleCountText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shop);

        db  = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        initViews();
        startUserListener();
    }

    private void initViews() {
        balanceText       = findViewById(R.id.shopBalanceText);
        shieldCountText   = findViewById(R.id.shopShieldCount);
        selfhealCountText = findViewById(R.id.shopSelfhealCount);
        invisibleCountText= findViewById(R.id.shopInvisibleCount);

        findViewById(R.id.shopBackButton).setOnClickListener(v -> finish());

        // Кнопки покупки
        Button buyShield    = findViewById(R.id.shopBuyShield);
        Button buySelfheal  = findViewById(R.id.shopBuySelfheal);
        Button buyInvisible = findViewById(R.id.shopBuyInvisible);

        buyShield.setOnClickListener(v -> confirmPurchase("shield", "Защита от мафии",
                DiamondManager.PERK_SHIELD_COST));
        buySelfheal.setOnClickListener(v -> confirmPurchase("selfheal", "Самолечение",
                DiamondManager.PERK_SELFHEAL_COST));
        buyInvisible.setOnClickListener(v -> confirmPurchase("invisible", "Невидимка",
                DiamondManager.PERK_INVISIBLE_COST));
    }

    private void startUserListener() {
        userListener = db.collection("users").document(uid)
                .addSnapshotListener((snap, e) -> {
                    if (snap == null || !snap.exists()) return;
                    long diamonds    = snap.getLong("diamonds")    != null ? snap.getLong("diamonds")    : 0L;
                    long shield      = snap.getLong("perk_shield")   != null ? snap.getLong("perk_shield")   : 0L;
                    long selfheal    = snap.getLong("perk_selfheal") != null ? snap.getLong("perk_selfheal") : 0L;
                    long invisible   = snap.getLong("perk_invisible")!= null ? snap.getLong("perk_invisible"): 0L;

                    balanceText.setText("💎 " + diamonds);
                    shieldCountText.setText("x" + shield);
                    selfhealCountText.setText("x" + selfheal);
                    invisibleCountText.setText("x" + invisible);
                });
    }

    private void confirmPurchase(String perkType, String perkName, long cost) {
        MafiaDialogs.confirm(this,
                "Купить «" + perkName + "»?",
                "Стоимость: " + cost + " 💎\n\nЭта плюшка будет добавлена в ваш инвентарь.",
                "КУПИТЬ", "ОТМЕНА",
                () -> doPurchase(perkType, perkName, cost), null);
    }

    private void doPurchase(String perkType, String perkName, long cost) {
        DiamondManager.purchasePerk(db, uid, perkType, new DiamondManager.OnPurchaseResult() {
            @Override public void onSuccess() {
                Toast.makeText(ShopActivity.this,
                        "«" + perkName + "» куплена! ✓", Toast.LENGTH_SHORT).show();
            }
            @Override public void onNotEnoughDiamonds() {
                showNotEnoughDialog(cost);
            }
            @Override public void onError(String message) {
                Toast.makeText(ShopActivity.this, "Ошибка покупки", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showNotEnoughDialog(long cost) {
        MafiaDialogs.confirm(this,
                "Недостаточно алмазов 💎",
                "Для покупки нужно " + cost + " 💎.\n\nСыграйте ещё несколько игр, чтобы накопить нужную сумму!",
                "СЫГРАТЬ", "ЗАКРЫТЬ",
                () -> finish(), null);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (userListener != null) userListener.remove();
    }
}

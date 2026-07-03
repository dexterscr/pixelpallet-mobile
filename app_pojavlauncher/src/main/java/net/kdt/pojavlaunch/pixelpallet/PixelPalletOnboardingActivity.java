package net.kdt.pojavlaunch.pixelpallet;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import net.kdt.pojavlaunch.R;

/**
 * Tutorial de boas-vindas do PixelPallet, exibido só na primeira execução.
 * Explica, em poucos slides, como usar o launcher (é só tocar em JOGAR).
 */
public class PixelPalletOnboardingActivity extends AppCompatActivity {

    /** Marca que o usuário já viu o tutorial. */
    public static final String PREF_KEY_ONBOARDING_SEEN = "pixelpallet_onboarding_seen_v1";

    private static final int[] IMAGES = {
            R.drawable.onb_welcome, R.drawable.onb_oneclick, R.drawable.onb_optimized
    };
    private static final int[] TITLES = {
            R.string.pp_onb_1_title, R.string.pp_onb_2_title, R.string.pp_onb_3_title
    };
    private static final int[] DESCS = {
            R.string.pp_onb_1_desc, R.string.pp_onb_2_desc, R.string.pp_onb_3_desc
    };

    /** Deve mostrar o tutorial? (true = ainda não foi visto) */
    public static boolean shouldShow(Context ctx) {
        return !PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_KEY_ONBOARDING_SEEN, false);
    }

    private ViewPager2 mPager;
    private LinearLayout mDots;
    private Button mNextButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pp_onboarding);

        mPager = findViewById(R.id.pp_onb_pager);
        mDots = findViewById(R.id.pp_onb_dots);
        mNextButton = findViewById(R.id.pp_onb_next);
        TextView skip = findViewById(R.id.pp_onb_skip);

        mPager.setAdapter(new SlideAdapter());
        buildDots();
        updateForPage(0);

        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateForPage(position); }
        });

        skip.setOnClickListener(v -> finishOnboarding());
        mNextButton.setOnClickListener(v -> {
            int current = mPager.getCurrentItem();
            if (current < IMAGES.length - 1) mPager.setCurrentItem(current + 1, true);
            else finishOnboarding();
        });
    }

    private void finishOnboarding() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(PREF_KEY_ONBOARDING_SEEN, true).apply();
        finish();
    }

    private void buildDots() {
        mDots.removeAllViews();
        int size = (int) (10 * getResources().getDisplayMetrics().density);
        int margin = (int) (4 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < IMAGES.length; i++) {
            ImageView dot = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, size);
            lp.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(lp);
            mDots.addView(dot);
        }
    }

    private void updateForPage(int position) {
        for (int i = 0; i < mDots.getChildCount(); i++) {
            ((ImageView) mDots.getChildAt(i)).setImageResource(
                    i == position ? R.drawable.pp_onb_dot_active : R.drawable.pp_onb_dot_inactive);
        }
        boolean last = position == IMAGES.length - 1;
        mNextButton.setText(last ? R.string.pp_onb_start : R.string.pp_onb_next);
    }

    private class SlideAdapter extends RecyclerView.Adapter<SlideAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pp_onboarding_slide, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.image.setImageResource(IMAGES[position]);
            holder.title.setText(TITLES[position]);
            holder.desc.setText(DESCS[position]);
        }

        @Override public int getItemCount() { return IMAGES.length; }

        class VH extends RecyclerView.ViewHolder {
            final ImageView image; final TextView title; final TextView desc;
            VH(@NonNull View v) {
                super(v);
                image = v.findViewById(R.id.pp_onb_image);
                title = v.findViewById(R.id.pp_onb_title);
                desc = v.findViewById(R.id.pp_onb_desc);
            }
        }
    }
}

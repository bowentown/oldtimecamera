package com.bowentown.oldtimecamera;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

/** Prompts for a mood description and translates it into a live {@link FilmRecipe} proposal via BYOK AI. */
public final class AiRecipeDialog {

    public interface OnProposalListener {
        void onProposal(AiCoachMath.AiRecipeProposal proposal);
    }

    private AiRecipeDialog() {}

    public static void show(Context context, CameraPrefs prefs, String fallbackPresetId,
                            OnProposalListener listener) {
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        if (!AiKeyStore.hasApiKey(context)) {
            AiSettingsDialog.show(context, prefs, () -> show(context, prefs, fallbackPresetId, listener));
            return;
        }

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_ai_recipe);
        FilmUiHelper.configureBottomSheet(dialog, true);

        EditText promptInput = dialog.findViewById(R.id.aiRecipePromptInput);
        TextView status = dialog.findViewById(R.id.aiRecipeStatus);
        TextView generateBtn = dialog.findViewById(R.id.aiRecipeGenerateBtn);
        dialog.findViewById(R.id.aiRecipeCloseBtn).setOnClickListener(v -> dialog.dismiss());

        int[] tagIds = {R.id.aiRecipeTag1, R.id.aiRecipeTag2, R.id.aiRecipeTag3};
        for (int id : tagIds) {
            TextView tag = dialog.findViewById(id);
            tag.setOnClickListener(v -> {
                String txt = tag.getText().toString();
                promptInput.setText(txt);
                promptInput.setSelection(txt.length());
            });
        }

        boolean zh = context.getResources().getConfiguration().getLocales().get(0)
                .getLanguage().startsWith("zh");

        generateBtn.setOnClickListener(v -> {
            String query = promptInput.getText().toString().trim();
            if (query.isEmpty()) {
                query = context.getString(R.string.ai_recipe_tag_1);
                promptInput.setText(query);
            }
            final String finalQuery = query;
            generateBtn.setEnabled(false);
            generateBtn.setAlpha(0.6f);
            status.setText(R.string.ai_recipe_generating);
            status.setTextColor(context.getColor(R.color.amber_led));
            status.setVisibility(View.VISIBLE);

            new Thread(() -> {
                AiCoachMath.AiRecipeProposal proposal = null;
                String err = null;
                try {
                    proposal = AiCoachClient.generateRecipe(
                            context, prefs, finalQuery, fallbackPresetId, zh);
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final AiCoachMath.AiRecipeProposal res = proposal;
                final String failure = err;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) {
                        return;
                    }
                    generateBtn.setEnabled(true);
                    generateBtn.setAlpha(1f);
                    if (res != null) {
                        dialog.dismiss();
                        if (listener != null) listener.onProposal(res);
                    } else {
                        status.setText(String.valueOf(failure));
                        status.setTextColor(context.getColor(R.color.accent));
                    }
                });
            }, "otc-ai-recipe").start();
        });

        dialog.show();
    }
}

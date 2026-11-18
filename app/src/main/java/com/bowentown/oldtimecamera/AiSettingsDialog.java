package com.bowentown.oldtimecamera;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Matte-black bottom dialog for BYOK AI configuration (DeepSeek default + OpenAI-compatible endpoints). */
public final class AiSettingsDialog {

    private AiSettingsDialog() {}

    public static void show(Context context, CameraPrefs prefs, Runnable onSaved) {
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_ai_settings);
        FilmUiHelper.configureBottomSheet(dialog, true);

        Typeface mono = FilmUiHelper.monoFont(context);
        EditText keyInput = dialog.findViewById(R.id.aiKeyInput);
        EditText urlInput = dialog.findViewById(R.id.aiBaseUrlInput);
        EditText modelInput = dialog.findViewById(R.id.aiModelInput);
        TextView keyStatus = dialog.findViewById(R.id.aiKeyStatus);
        TextView testStatus = dialog.findViewById(R.id.aiTestStatus);
        Switch visionSwitch = dialog.findViewById(R.id.aiVisionSwitch);
        TextView testBtn = dialog.findViewById(R.id.aiTestBtn);
        TextView clearBtn = dialog.findViewById(R.id.aiClearKeyBtn);
        TextView saveBtn = dialog.findViewById(R.id.aiSaveBtn);
        LinearLayout providerSeg = dialog.findViewById(R.id.aiProviderSeg);

        dialog.findViewById(R.id.aiCloseButton).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.aiVisionRow).setOnClickListener(v -> visionSwitch.toggle());

        final AiCoachMath.ProviderPreset[] presets = AiCoachMath.ProviderPreset.values();
        final AiCoachMath.ProviderPreset[] selectedPreset = {prefs.getAiProviderPreset()};
        String[] providerLabels = {
                context.getString(R.string.ai_provider_deepseek),
                context.getString(R.string.ai_provider_qwen),
                context.getString(R.string.ai_provider_openai),
                context.getString(R.string.ai_provider_custom)
        };

        urlInput.setText(prefs.getAiBaseUrl());
        modelInput.setText(prefs.getAiModel());
        visionSwitch.setChecked(prefs.isAiVisionEnabled());
        refreshKeyBadge(context, keyStatus, keyInput);

        FilmUiHelper.buildSegmented(context, mono, providerSeg, providerLabels,
                selectedPreset[0].ordinal(), idx -> {
                    AiCoachMath.ProviderPreset chosen = presets[idx];
                    selectedPreset[0] = chosen;
                    if (chosen != AiCoachMath.ProviderPreset.CUSTOM) {
                        urlInput.setText(chosen.defaultBaseUrl);
                        modelInput.setText(chosen.defaultModel);
                        visionSwitch.setChecked(chosen.defaultVision);
                    }
                    testStatus.setVisibility(View.GONE);
                });

        clearBtn.setOnClickListener(v -> {
            AiKeyStore.clearApiKey(context);
            keyInput.setText("");
            refreshKeyBadge(context, keyStatus, keyInput);
            testStatus.setText(R.string.ai_cleared);
            testStatus.setTextColor(context.getColor(R.color.paper_dim));
            testStatus.setVisibility(View.VISIBLE);
        });

        testBtn.setOnClickListener(v -> {
            String rawUrl = urlInput.getText().toString().trim();
            String rawModel = modelInput.getText().toString().trim();
            String rawKey = keyInput.getText().toString().trim();
            testBtn.setEnabled(false);
            testStatus.setText(R.string.ai_testing);
            testStatus.setTextColor(context.getColor(R.color.paper_soft));
            testStatus.setVisibility(View.VISIBLE);

            boolean zh = context.getResources().getConfiguration().getLocales().get(0)
                    .getLanguage().startsWith("zh");
            new Thread(() -> {
                String okText = null;
                String errText = null;
                try {
                    AiCoachClient.PingResult res =
                            AiCoachClient.testConnection(context, rawUrl, rawModel, rawKey, zh);
                    okText = context.getString(R.string.ai_test_ok, res.latencyMs);
                } catch (Exception e) {
                    errText = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final String ok = okText;
                final String err = errText;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) {
                        return;
                    }
                    testBtn.setEnabled(true);
                    if (ok != null) {
                        testStatus.setText(ok);
                        testStatus.setTextColor(context.getColor(R.color.amber_led));
                    } else {
                        testStatus.setText(context.getString(R.string.ai_test_fail, String.valueOf(err)));
                        testStatus.setTextColor(context.getColor(R.color.accent));
                    }
                });
            }, "otc-ai-ping").start();
        });

        saveBtn.setOnClickListener(v -> {
            String rawUrl = urlInput.getText().toString().trim();
            String normalized = AiCoachMath.normalizeEndpointUrl(rawUrl);
            if (!AiCoachMath.isAllowedEndpointUrl(normalized)) {
                testStatus.setText(R.string.ai_err_https_required);
                testStatus.setTextColor(context.getColor(R.color.accent));
                testStatus.setVisibility(View.VISIBLE);
                return;
            }
            prefs.setAiProviderPreset(selectedPreset[0]);
            prefs.setAiBaseUrl(rawUrl.isEmpty() ? selectedPreset[0].defaultBaseUrl : rawUrl);
            String model = modelInput.getText().toString().trim();
            prefs.setAiModel(model.isEmpty() ? selectedPreset[0].defaultModel : model);
            prefs.setAiVisionEnabled(visionSwitch.isChecked());

            String typedKey = keyInput.getText().toString().trim();
            if (!typedKey.isEmpty()) {
                AiKeyStore.saveApiKey(context, typedKey);
            }

            dialog.dismiss();
            Toast.makeText(context, R.string.ai_saved, Toast.LENGTH_SHORT).show();
            if (onSaved != null) {
                onSaved.run();
            }
        });

        dialog.show();
    }

    static String formatSummary(Context context, CameraPrefs prefs) {
        String existing = AiKeyStore.loadApiKey(context);
        if (existing.isEmpty()) {
            return context.getString(R.string.ai_status_unconfigured);
        }
        return prefs.getAiProviderPreset().label + " · " + AiCoachMath.maskApiKey(existing);
    }

    private static void refreshKeyBadge(Context context, TextView status, EditText input) {
        String existing = AiKeyStore.loadApiKey(context);
        if (existing.isEmpty()) {
            status.setText(R.string.ai_key_missing);
            status.setTextColor(context.getColor(R.color.paper_dim));
            input.setHint(R.string.ai_key_hint_new);
        } else {
            status.setText(context.getString(R.string.ai_key_saved_mask, AiCoachMath.maskApiKey(existing)));
            status.setTextColor(context.getColor(R.color.amber_led));
            input.setHint(R.string.ai_key_hint_keep);
        }
    }
}

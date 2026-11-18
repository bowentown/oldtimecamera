package com.bowentown.oldtimecamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed encrypted storage for the user's AI API key via AndroidKeyStore
 * (AES/GCM/NoPadding, API 26+). The raw secret is never written in plaintext and
 * never leaves the device except in the explicit HTTPS request the user triggers.
 */
public final class AiKeyStore {

    private static final String PREFS_NAME = "old_time_camera_ai_secrets";
    private static final String KEY_ALIAS = "otc_ai_api_key_v1";
    private static final String KEY_IV = "iv";
    private static final String KEY_CIPHER = "cipher";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private static volatile String volatileSessionFallback = "";

    private AiKeyStore() {}

    public static synchronized boolean saveApiKey(Context context, String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) {
            clearApiKey(context);
            return true;
        }
        SharedPreferences sp = prefs(context);
        try {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
            sp.edit()
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .apply();
            volatileSessionFallback = "";
            return true;
        } catch (Exception e) {
            // Keep encrypted storage strict: never write plaintext if KeyStore is unavailable.
            volatileSessionFallback = key;
            return false;
        }
    }

    public static synchronized String loadApiKey(Context context) {
        if (volatileSessionFallback != null && !volatileSessionFallback.isEmpty()) {
            return volatileSessionFallback;
        }
        SharedPreferences sp = prefs(context);
        String ivB64 = sp.getString(KEY_IV, null);
        String cipherB64 = sp.getString(KEY_CIPHER, null);
        if (ivB64 == null || cipherB64 == null) {
            return "";
        }
        try {
            SecretKey secretKey = getExistingKey();
            if (secretKey == null) return "";
            byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(cipherB64, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasApiKey(Context context) {
        return !loadApiKey(context).isEmpty();
    }

    public static synchronized void clearApiKey(Context context) {
        volatileSessionFallback = "";
        prefs(context).edit().remove(KEY_IV).remove(KEY_CIPHER).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SecretKey getExistingKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        return ks.containsAlias(KEY_ALIAS) ? (SecretKey) ks.getKey(KEY_ALIAS, null) : null;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        SecretKey existing = getExistingKey();
        if (existing != null) return existing;
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}

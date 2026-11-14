package com.bowentown.oldtimecamera;

import android.app.AlertDialog;
import android.app.RecoverableSecurityException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app darkroom archive: browse exposed frames, inspect in a swipeable
 * lightbox, share/delete, and assemble 35mm contact sheets.
 */
public final class GalleryActivity extends ComponentActivity {

    private final List<PhotoSaver.PhotoItem> items = new ArrayList<>();
    private final Set<Uri> selectedUris = new LinkedHashSet<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private LruCache<String, Bitmap> thumbCache;
    private GridView gridView;
    private TextView subtitleView, emptyView, sheetToggleBtn, makeSheetBtn;
    private LinearLayout sheetSelectBar;
    private View lightboxOverlay;
    private ImageView lightboxImage;
    private TextView lightboxTitle;

    private CameraPrefs prefs;
    private FrameAdapter adapter;
    private boolean selectMode = false;
    private int lightboxIndex = -1;
    private Uri pendingDeleteUri;
    private Bitmap lightboxBitmap;

    private final ActivityResultLauncher<IntentSenderRequest> deleteSenderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    onPhotoRemoved(pendingDeleteUri);
                }
                pendingDeleteUri = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        prefs = new CameraPrefs(this);
        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L);
        thumbCache = new LruCache<String, Bitmap>(Math.max(4096, cacheKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        View root = findViewById(R.id.galleryRoot);
        FilmUiHelper.applySystemBarPadding(root);

        gridView = findViewById(R.id.galleryGrid);
        subtitleView = findViewById(R.id.gallerySubtitle);
        emptyView = findViewById(R.id.galleryEmpty);
        sheetToggleBtn = findViewById(R.id.gallerySheetToggle);
        sheetSelectBar = findViewById(R.id.sheetSelectBar);
        makeSheetBtn = findViewById(R.id.makeSheetButton);
        lightboxOverlay = findViewById(R.id.lightboxOverlay);
        lightboxImage = findViewById(R.id.lightboxImage);
        lightboxTitle = findViewById(R.id.lightboxTitle);

        adapter = new FrameAdapter();
        gridView.setAdapter(adapter);

        findViewById(R.id.galleryBack).setOnClickListener(v -> finish());
        sheetToggleBtn.setOnClickListener(v -> toggleSelectMode());
        makeSheetBtn.setOnClickListener(v -> exportContactSheet());

        findViewById(R.id.lightboxClose).setOnClickListener(v -> closeLightbox());
        findViewById(R.id.lightboxShare).setOnClickListener(v -> shareCurrent());
        findViewById(R.id.lightboxDelete).setOnClickListener(v -> confirmDeleteCurrent());

        setupLightboxSwipe();
        loadPhotos();
    }

    private void setupLightboxSwipe() {
        GestureDetector swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > 90 && Math.abs(vx) > 200) {
                    if (dx < 0 && lightboxIndex + 1 < items.size()) {
                        openLightbox(lightboxIndex + 1);
                        return true;
                    } else if (dx > 0 && lightboxIndex - 1 >= 0) {
                        openLightbox(lightboxIndex - 1);
                        return true;
                    }
                }
                return false;
            }
        });
        lightboxImage.setOnTouchListener((v, event) -> swipe.onTouchEvent(event));
    }

    private void loadPhotos() {
        worker.execute(() -> {
            List<PhotoSaver.PhotoItem> loaded = PhotoSaver.loadAlbumPhotos(this);
            main.post(() -> {
                items.clear();
                items.addAll(loaded);
                adapter.notifyDataSetChanged();
                subtitleView.setText(getString(R.string.gallery_frames, items.size()));
                emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                sheetToggleBtn.setVisibility(items.size() >= 2 ? View.VISIBLE : View.GONE);
                if (!items.isEmpty()) {
                    prefs.setLastPhotoUri(items.get(0).uri);
                } else {
                    prefs.setLastPhotoUri(null);
                }
            });
        });
    }

    private void toggleSelectMode() {
        selectMode = !selectMode;
        selectedUris.clear();
        sheetSelectBar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        sheetToggleBtn.setText(selectMode ? R.string.cancel : R.string.gallery_contact_sheet);
        updateMakeSheetButton();
        adapter.notifyDataSetChanged();
    }

    private void updateMakeSheetButton() {
        int count = selectedUris.size();
        makeSheetBtn.setText(getString(R.string.gallery_make_sheet, count));
        boolean ready = count >= 2;
        makeSheetBtn.setAlpha(ready ? 1f : 0.42f);
        makeSheetBtn.setEnabled(ready);
    }

    private void exportContactSheet() {
        if (selectedUris.size() < 2) return;
        List<Uri> copy = new ArrayList<>(selectedUris);
        makeSheetBtn.setEnabled(false);
        worker.execute(() -> {
            try {
                Uri out = PhotoSaver.buildContactSheet(this, copy, prefs.getRollNumber());
                prefs.setLastPhotoUri(out);
                main.post(() -> {
                    Toast.makeText(this, R.string.gallery_sheet_saved, Toast.LENGTH_SHORT).show();
                    if (selectMode) toggleSelectMode();
                    loadPhotos();
                });
            } catch (Exception e) {
                main.post(() -> {
                    makeSheetBtn.setEnabled(true);
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void openLightbox(int index) {
        if (index < 0 || index >= items.size()) return;
        lightboxIndex = index;
        PhotoSaver.PhotoItem item = items.get(index);
        String dateLabel = item.dateMillis > 0
                ? new SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.US).format(new Date(item.dateMillis))
                : item.displayName;
        lightboxTitle.setText(String.format(Locale.US, "%d / %d  ·  %s",
                index + 1, items.size(), dateLabel));
        lightboxOverlay.setVisibility(View.VISIBLE);

        worker.execute(() -> {
            Bitmap bmp = PhotoSaver.decodeScaledUri(this, item.uri, 2048);
            main.post(() -> {
                if (lightboxIndex != index) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                Bitmap old = lightboxBitmap;
                lightboxBitmap = bmp;
                lightboxImage.setImageBitmap(bmp);
                if (old != null && !old.isRecycled()) old.recycle();
            });
        });
    }

    private void closeLightbox() {
        lightboxOverlay.setVisibility(View.GONE);
        lightboxIndex = -1;
        lightboxImage.setImageDrawable(null);
        if (lightboxBitmap != null && !lightboxBitmap.isRecycled()) {
            lightboxBitmap.recycle();
            lightboxBitmap = null;
        }
    }

    private void shareCurrent() {
        if (lightboxIndex < 0 || lightboxIndex >= items.size()) return;
        Uri uri = items.get(lightboxIndex).uri;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/jpeg");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, getString(R.string.gallery_share)));
    }

    private void confirmDeleteCurrent() {
        if (lightboxIndex < 0 || lightboxIndex >= items.size()) return;
        Uri uri = items.get(lightboxIndex).uri;
        new AlertDialog.Builder(this)
                .setMessage(R.string.gallery_delete_confirm)
                .setPositiveButton(R.string.gallery_delete, (d, w) -> performDelete(uri))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void performDelete(Uri uri) {
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                File f = new File(uri.getPath());
                if (f.delete()) {
                    onPhotoRemoved(uri);
                }
                return;
            }
            int deleted = getContentResolver().delete(uri, null, null);
            if (deleted > 0) {
                onPhotoRemoved(uri);
                return;
            }
        } catch (SecurityException se) {
            if (Build.VERSION.SDK_INT >= 30) {
                launchDeleteRequestApi30(uri);
                return;
            } else if (Build.VERSION.SDK_INT == 29) {
                handleRecoverableApi29(uri, se);
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            launchDeleteRequestApi30(uri);
        }
    }

    @RequiresApi(30)
    private void launchDeleteRequestApi30(Uri uri) {
        pendingDeleteUri = uri;
        IntentSenderRequest req = new IntentSenderRequest.Builder(
                MediaStore.createDeleteRequest(getContentResolver(),
                        Collections.singletonList(uri)).getIntentSender()).build();
        deleteSenderLauncher.launch(req);
    }

    @RequiresApi(29)
    private void handleRecoverableApi29(Uri uri, SecurityException se) {
        if (se instanceof RecoverableSecurityException) {
            pendingDeleteUri = uri;
            RecoverableSecurityException rse = (RecoverableSecurityException) se;
            IntentSenderRequest req = new IntentSenderRequest.Builder(
                    rse.getUserAction().getActionIntent().getIntentSender()).build();
            deleteSenderLauncher.launch(req);
        }
    }

    private void onPhotoRemoved(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, R.string.gallery_deleted, Toast.LENGTH_SHORT).show();
        closeLightbox();
        thumbCache.remove(uri.toString());
        loadPhotos();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (lightboxOverlay.getVisibility() == View.VISIBLE) {
            closeLightbox();
            return;
        }
        if (selectMode) {
            toggleSelectMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        closeLightbox();
        thumbCache.evictAll();
    }

    private final class FrameAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout cell;
            ImageView img;
            ImageView check;
            if (convertView == null) {
                int cellSize = (parent.getWidth() > 0)
                        ? (parent.getWidth() - FilmUiHelper.dp(GalleryActivity.this, 20)) / 3
                        : FilmUiHelper.dp(GalleryActivity.this, 112);
                cell = new FrameLayout(GalleryActivity.this);
                cell.setLayoutParams(new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cellSize));
                cell.setBackgroundResource(R.drawable.bg_gallery_cell);
                cell.setClipToOutline(true);

                img = new ImageView(GalleryActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(img, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                check = new ImageView(GalleryActivity.this);
                int badgeSize = FilmUiHelper.dp(GalleryActivity.this, 22);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                        badgeSize, badgeSize, Gravity.TOP | Gravity.END);
                int m = FilmUiHelper.dp(GalleryActivity.this, 6);
                clp.setMargins(m, m, m, m);
                int p = FilmUiHelper.dp(GalleryActivity.this, 4);
                check.setPadding(p, p, p, p);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(0xFFF0E8D8);
                bg.setStroke(FilmUiHelper.dp(GalleryActivity.this, 1), 0x801A1714);
                check.setBackground(bg);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.ink)));
                cell.addView(check, clp);

                cell.setTag(new View[]{img, check});
            } else {
                cell = (FrameLayout) convertView;
                View[] refs = (View[]) cell.getTag();
                img = (ImageView) refs[0];
                check = (ImageView) refs[1];
            }

            PhotoSaver.PhotoItem item = items.get(position);
            String key = item.uri.toString();
            img.setTag(key);

            boolean isSelected = selectMode && selectedUris.contains(item.uri);
            check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            img.setAlpha(selectMode && !isSelected ? 0.72f : 1f);

            Bitmap cached = thumbCache.get(key);
            if (cached != null && !cached.isRecycled()) {
                img.setImageBitmap(cached);
            } else {
                img.setImageDrawable(null);
                worker.execute(() -> {
                    Bitmap bmp = PhotoSaver.decodeScaledUri(GalleryActivity.this, item.uri, 360);
                    if (bmp != null) {
                        thumbCache.put(key, bmp);
                        main.post(() -> {
                            if (key.equals(img.getTag())) {
                                img.setImageBitmap(bmp);
                            }
                        });
                    }
                });
            }

            cell.setOnClickListener(v -> {
                if (selectMode) {
                    if (selectedUris.contains(item.uri)) {
                        selectedUris.remove(item.uri);
                    } else if (selectedUris.size() < 9) {
                        selectedUris.add(item.uri);
                    } else {
                        return;
                    }
                    updateMakeSheetButton();
                    notifyDataSetChanged();
                } else {
                    openLightbox(position);
                }
            });

            return cell;
        }
    }
}

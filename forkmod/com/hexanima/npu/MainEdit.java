package com.hexanima.npu;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.widget.*;
import java.io.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** part of the main screen (split out of Main.java): MainEdit */
abstract class MainEdit extends MainTools {
    List<File> batchFiles = new ArrayList<>();
    abstract Fold fold(LinearLayout parent, String title, String key);
    LinearLayout extrasBox; TextView initText, adText, queueText; EditText strEdit, hsEdit, hdEdit, hnEdit;
    CheckBox hiresAuto, hiresBig;
    TextView hiresHint;
    LinearLayout animaInitBox; ImageView initThumb; TextView initInfo, initStrVal; Button initDrop, initMaskB; SeekBar initStr;
    File batchGridFile; final List<File> extraGrids = new ArrayList<>();   // a large batch: its first contact sheet, then the others (9 pictures each at most)
    // mod: Hires fix redo — run the second pass on a picture from the album or from the History instead of on a picture just generated
    static final int REQ_HIRES_PICK = 34;
    View hiresRow;   // the row of the two import buttons (the "..." menu scrolls the screen down to it)
    boolean customOpen;
    ImageButton dlBtn, shareBtn, favBtn, againBtn, upBtn, fixBtn; Button editGeneratedBtn;
    Fold sampFold, loraFold, extFold;
    abstract EditText numField(LinearLayout row, String title, String value, boolean decimal, OnText cb);
    void refreshEdit() {
        if (editBm == null && prefs().getBoolean("qwen_edit_on", false) && Qwen.editSrc(getFilesDir()).exists()) editBm = BitmapFactory.decodeFile(Qwen.editSrc(getFilesDir()).getPath());
        final Bitmap b = editBm;
        editThumb.setImageBitmap(b); editThumb.setVisibility(b != null ? View.VISIBLE : View.GONE);
        boolean masked = Qwen.editMask(getFilesDir()).isFile();
        editInfo.setText(b != null ? isMage() ? L.f("Foto %d×%d. Describe cómo transformarla.", "Photo %d×%d. Describe how to transform it.", b.getWidth(), b.getHeight()) + (masked ? L.s("\nMáscara: Mage cambia solo la zona pintada; el resto de la foto y su resolución se conservan.", "\nMask: Mage changes only the painted area; the rest of the photo and its resolution are preserved.") : L.s("\nSin máscara, Mage redibuja la imagen completa y puede alterar textos, manos o ropa. Usa «Área a cambiar» para conservar el resto.", "\nWithout a mask, Mage redraws the whole picture and may alter text, hands or clothes. Use Area to edit to keep the rest.")) : L.f("Фото %d×%d. Describe qué cambiar, por ejemplo «haz que el cielo parezca de atardecer». Pinta solo la zona que quieras editar.", "Photo %d×%d. The prompt says what to change, e.g. \"make the sky a sunset\". Paint only the area to edit.", b.getWidth(), b.getHeight()) + (masked ? L.s("\nMáscara: Qwen cambia la zona pintada; el resto de la foto y su resolución se conservan.", "\nMask: Qwen changes the painted area; the rest of the photo and its resolution are preserved.") : L.s("\nSin máscara, Qwen puede redibujar toda la foto y alterar detalles finos. Usa «Máscara» para conservar el resto.", "\nWithout a mask, Qwen can redraw the whole photo and alter fine details. Use Mask to preserve the rest."))
            : L.s("Выберите фото, чтобы изменить его по описанию. Без фото модель просто рисует картинку.", "Pick a photo to change it by the prompt. Without one the model just draws a picture."));
        editDrop.setVisibility(b != null ? View.VISIBLE : View.GONE);
        final java.util.List<File> refs = b != null && (isQwen() || isMage()) ? Qwen.extraRefs(getFilesDir()) : new ArrayList<>();   // Mage takes reference pictures too
        editMaskBtn.setVisibility(b != null ? View.VISIBLE : View.GONE);   // the painted area is on photo 1, with or without reference pictures
        editMaskClear.setVisibility(b != null && masked ? View.VISIBLE : View.GONE);
        if (refsRow != null) {
            refsRow.removeAllViews(); refsRow.setVisibility(refs.isEmpty() ? View.GONE : View.VISIBLE);
            if (!refs.isEmpty()) { TextView one = text(L.s("1 = esta foto", "1 = this photo"), 12, DIM, false); one.setPadding(dp(2), 0, dp(8), 0); refsRow.addView(one); }
            for (int i = 0; i < refs.size(); i++) {
                final File rf = refs.get(i); FrameLayout fr = new FrameLayout(this);
                ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackground(bg(CARD, 12)); iv.setClipToOutline(true);
                Bitmap tb = BitmapFactory.decodeFile(rf.getPath()); if (tb != null) iv.setImageBitmap(Bitmap.createScaledBitmap(tb, 128, Math.max(1, 128 * tb.getHeight() / tb.getWidth()), true));
                fr.addView(iv, new FrameLayout.LayoutParams(dp(56), dp(56)));
                TextView num = text(String.valueOf(i + 2), 12, ON_ACC, true); num.setGravity(Gravity.CENTER); num.setBackground(bg(ACC, 9));
                fr.addView(num, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.START));
                TextView x = text("✕", 12, TXT, true); x.setGravity(Gravity.CENTER); x.setBackground(bg(FIELD, 9));
                x.setOnClickListener(v -> { rf.delete(); new File(rf.getPath() + ".orig").delete(); java.util.List<File> left = Qwen.extraRefs(getFilesDir()); for (int k = 0; k < left.size(); k++) left.get(k).renameTo(Qwen.refFile(getFilesDir(), k + 2)); refreshEdit(); });
                fr.addView(x, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END));
                fr.setOnClickListener(v -> refMenu(rf));   // tap: use only its pose (silhouette)
                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(56), dp(56)); fl.rightMargin = dp(8); refsRow.addView(fr, fl);
            }
            if (!refs.isEmpty()) { TextView how = text(L.s("Describe lo que tomas de la imagen 2, p. ej.: «la chica de la imagen 1 con el bikini negro de lunares de la imagen 2, mantén su cara y su pelo» o «…agachada con los dos brazos arriba, como en la imagen 2». Solo «la ropa de la imagen 2» no basta.", "Describe what to take from image 2, e.g. \"the girl from image 1 in the black polka-dot bikini from image 2, keep her face and hair\" or \"...squatting with both arms raised, as in image 2\". Just \"the outfit of image 2\" is not enough."), 12, DIM, false); refsRow.addView(how, new LinearLayout.LayoutParams(0, -2, 1)); }
        }
        if (refActs != null) {
            refActs.removeAllViews(); refActs.setVisibility(refs.isEmpty() ? View.GONE : View.VISIBLE);
            if (!refs.isEmpty()) {
                LinearLayout br = new LinearLayout(this); br.setPadding(0, dp(6), 0, 0);
                Button cc = button(L.s("👗 Copiar su ropa", "👗 Copy its outfit"), CARD, ACC); cc.setTextSize(12); cc.setAllCaps(false); cc.setOnClickListener(v -> copyFromRef(true));
                Button cp = button(L.s("🧍 Copiar su pose", "🧍 Copy its pose"), CARD, ACC); cp.setTextSize(12); cp.setAllCaps(false); cp.setOnClickListener(v -> copyFromRef(false));
                br.addView(cc, new LinearLayout.LayoutParams(0, dp(40), 1)); LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(0, dp(40), 1); cpl.leftMargin = dp(6); br.addView(cp, cpl);
                refActs.addView(br);
                TextView ch = text(L.s("Mejor calidad: lee la imagen 2, escribe el prompt y edita solo la foto 1 a tamaño completo.", "Better quality: reads image 2, writes the prompt and edits only photo 1 at full size."), 11, DIM, false);
                ch.setPadding(dp(4), dp(2), dp(4), 0); refActs.addView(ch);
            }
        }
        if (editAddRef != null) editAddRef.setVisibility(b != null && (isQwen() || isMage()) && 1 + refs.size() < Qwen.MAX_REFS ? View.VISIBLE : View.GONE);
        if (editOpts != null) editOpts.setVisibility(b != null && isQwen() ? View.VISIBLE : View.GONE);
        if (editOpts != null && editOpts.getChildCount() > 0) editOpts.getChildAt(0).setVisibility(refs.isEmpty() ? View.VISIBLE : View.GONE);   // "photo's shape": with several pictures the picked size is used
    }
    void useShownImageAsQwenSource() {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la tanda", "Wait for the batch to finish"), Toast.LENGTH_SHORT).show(); return; }
        final File chosen = lastFile;
        if (chosen == null || !chosen.isFile() || chosen == batchGridFile) return;
        final boolean mage = isMage() && Mage.installed(getFilesDir());   // with Mage selected, the picture is edited by Mage; otherwise by Qwen-Image
        String qwenId = mage ? model : "";
        if (!mage) for (String id : store.installed()) if (Qwen.is(id)) { qwenId = id; break; }
        if (qwenId.isEmpty()) { Toast.makeText(this, L.s("Instala Qwen-Image antes de editar con él", "Install Qwen-Image before editing with it"), Toast.LENGTH_LONG).show(); return; }
        final String targetModel = qwenId;
        editGeneratedBtn.setEnabled(false);
        ex2.submit(() -> {
            Bitmap input = BitmapFactory.decodeFile(chosen.getPath());
            if (input == null) { ui.post(() -> { editGeneratedBtn.setEnabled(true); Toast.makeText(this, L.s("No se pudo abrir la imagen", "Could not open this image"), Toast.LENGTH_LONG).show(); }); return; }
            try {
                Bitmap next = Qwen.storePhoto(input, getFilesDir());
                if (next != input) input.recycle();
                Models.deleteTree(Qwen.editDir(getFilesDir()));
                prefs().edit().putBoolean("qwen_edit_on", true).apply();
                ui.post(() -> {
                    editGeneratedBtn.setEnabled(true); editBm = next; setModel(targetModel); refreshEdit();
                    double aspect = (double) next.getWidth() / next.getHeight(), best = 1e9; int[] pick = null;
                    if (!mage) for (int[] z : Qwen.PRESETS) { if (z[0] * z[1] > 800000) continue; double d = Math.abs(Math.log((double) z[0] / z[1] / aspect)) + Math.abs(z[0] * z[1] - 393216) / 5e7; if (d < best) { best = d; pick = z; } }
                    if (pick != null) { customOpen = false; setRes(pick[0], pick[1]); }
                    paintQwenMask();
                });
            } catch (Exception e) {
                if (!input.isRecycled()) input.recycle(); ui.post(() -> { editGeneratedBtn.setEnabled(true); Toast.makeText(this, L.f("No se pudo preparar la imagen: %s", "Could not prepare the image: %s", e.getMessage()), Toast.LENGTH_LONG).show(); });
            }
        });
    }
    void pickEdit() {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 10);
    }
    void dropEdit() {
        if (busy) return;
        editBm = null; prefs().edit().putBoolean("qwen_edit_on", false).apply();
        ex2.submit(() -> { Qwen.editSrc(getFilesDir()).delete(); Qwen.editMask(getFilesDir()).delete(); Qwen.clearRefs(getFilesDir()); Models.deleteTree(Qwen.editDir(getFilesDir())); ui.post(this::refreshEdit); });
        setRes(resW, resH);
    }
    void importEdit(Uri uri) {
        ex2.submit(() -> {
            try {
                final Bitmap bm = Qwen.importPhoto(getContentResolver(), uri, getFilesDir());
                prefs().edit().putBoolean("qwen_edit_on", true).apply();
                ui.post(() -> {
                    editBm = bm; refreshEdit();
                    // the output size follows the photo's shape: the preset with the closest aspect ratio, at most 0.8 Mpx
                    double r = (double) bm.getWidth() / bm.getHeight(), best = 1e9; int[] pick = null;
                    for (int[] z : Qwen.PRESETS) { if (z[0] * z[1] > 800000) continue; double d = Math.abs(Math.log((double) z[0] / z[1] / r)) + Math.abs(z[0] * z[1] - 393216) / 5e7; if (d < best) { best = d; pick = z; } }
                    if (pick != null) { customOpen = false; setRes(pick[0], pick[1]); }
                });
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Не удалось открыть фото: %s", "Cannot open the photo: %s", e.getMessage()), Toast.LENGTH_LONG).show()); }
        });
    }
    void buildAnimaInit() {
        animaInitBox = new LinearLayout(this); animaInitBox.setOrientation(LinearLayout.VERTICAL);
        initFold = fold(animaInitBox, L.s("EDITAR UNA FOTO  ·  opcional", "EDIT A PHOTO  ·  optional"), "ainit");   // folds away like Sampling; the header sums it up
        final LinearLayout ib = initFold.body;
        LinearLayout er = new LinearLayout(this); er.setGravity(Gravity.CENTER_VERTICAL);
        initThumb = new ImageView(this); initThumb.setScaleType(ImageView.ScaleType.CENTER_CROP); initThumb.setBackground(bg(CARD, 16)); initThumb.setClipToOutline(true);
        er.addView(initThumb, new LinearLayout.LayoutParams(dp(64), dp(64)));
        initInfo = text("", 13, DIM, false); initInfo.setPadding(dp(12), 0, dp(8), 0); er.addView(initInfo, new LinearLayout.LayoutParams(0, -2, 1));
        Button pick = button(L.s("Выбрать", "Choose"), CARD, TXT); pick.setOnClickListener(v -> pickImage(11));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, dp(44)); ep.leftMargin = dp(8); er.addView(pick, ep);
        initDrop = button("✕", CARD, TXT); initDrop.setContentDescription(L.s("Quitar la foto", "Remove the photo")); initDrop.setOnClickListener(v -> { initSrc().delete(); maskSrc().delete(); refreshExtras(); });
        LinearLayout.LayoutParams ed2 = new LinearLayout.LayoutParams(dp(48), dp(44)); ed2.leftMargin = dp(8); er.addView(initDrop, ed2);
        ib.addView(er);
        LinearLayout r2 = new LinearLayout(this); r2.setPadding(0, dp(6), 0, 0);
        Button last = button(L.s("↳ Последняя картинка", "↳ Last picture"), FIELD, ACC); last.setTextSize(12); last.setAllCaps(false); last.setOnClickListener(v -> useResultAsInit());
        r2.addView(last, new LinearLayout.LayoutParams(0, dp(38), 1));
        initMaskB = button(L.s("✎ Zona a cambiar", "✎ Area to change"), FIELD, ACC); initMaskB.setTextSize(12); initMaskB.setAllCaps(false); initMaskB.setOnClickListener(v -> paintMask());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, dp(38), 1); ml.leftMargin = dp(6); r2.addView(initMaskB, ml);
        ib.addView(r2);
        LinearLayout r3 = new LinearLayout(this); r3.setGravity(Gravity.CENTER_VERTICAL); r3.setPadding(0, dp(8), 0, 0);
        r3.addView(text(L.s("Cuánto cambia", "How much it changes"), 13, TXT, false));
        initStr = new SeekBar(this); initStr.setMax(18); initStr.setProgressTintList(ColorStateList.valueOf(ACC)); initStr.setThumbTintList(ColorStateList.valueOf(ACC));
        r3.addView(initStr, new LinearLayout.LayoutParams(0, -2, 1));
        initStrVal = text("", 13, ACC, true); r3.addView(initStrVal, new LinearLayout.LayoutParams(dp(52), -2));
        initStr.setProgress(Math.round((pf("x_strength", .6f) - 0.1f) / 0.05f));
        initStr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { float v = 0.1f + p * 0.05f; initStrVal.setText(Math.round(v * 100) + " %"); if (u) prefs().edit().putString("x_strength", String.format(java.util.Locale.US, "%.2f", v)).apply(); refreshAnimaInfo(); }
            public void onStartTrackingTouch(SeekBar sb) { } public void onStopTrackingTouch(SeekBar sb) { } });
        initStrVal.setText(Math.round(pf("x_strength", .6f) * 100) + " %");
        initStrVal.setPadding(dp(6), dp(8), dp(2), dp(8)); initStrVal.setBackground(bg(FIELD, 10)); initStrVal.setGravity(Gravity.CENTER);
        initStrVal.setOnClickListener(v -> {   // type the amount instead of sliding
            final EditText f = field("60"); f.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); f.setText(String.valueOf(Math.round(pf("x_strength", .6f) * 100))); f.selectAll();
            LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(f, new LinearLayout.LayoutParams(-1, -2));
            builder().setTitle(L.s("Cuánto cambia (%)", "How much it changes (%)")).setMessage(L.s("De 10 a 100.", "From 10 to 100.")).setView(box).setNegativeButton(L.s("Отмена", "Cancel"), null)
                .setPositiveButton("OK", (d, k) -> {
                    try { int pc = Math.max(10, Math.min(100, Integer.parseInt(f.getText().toString().trim())));
                        prefs().edit().putString("x_strength", String.format(java.util.Locale.US, "%.2f", pc / 100f)).apply();
                        initStr.setProgress(Math.round((pc / 100f - 0.1f) / 0.05f)); initStrVal.setText(pc + " %"); refreshAnimaInfo(); } catch (NumberFormatException ignored) { }
                }).show();
        });
        ib.addView(r3);
        TextView note = text(L.s("Poco: la foto casi igual con el estilo del modelo. Mucho: una imagen nueva que solo conserva la composición.", "Low: the photo almost unchanged, in the model's style. High: a new picture that keeps only the composition."), 11, DIM, false);
        note.setPadding(dp(4), dp(2), dp(4), 0); ib.addView(note);
        // pose control: the pose of a picture guides the new one (Pose.java)
        if (Pose.supported()) {
            poseFold = fold(animaInitBox, L.s("POSE  ·  opcional", "POSE  ·  optional"), "apose");
            final LinearLayout pb = poseFold.body;
            LinearLayout pr = new LinearLayout(this); pr.setGravity(Gravity.CENTER_VERTICAL);
            poseThumb = new ImageView(this); poseThumb.setScaleType(ImageView.ScaleType.CENTER_CROP); poseThumb.setBackground(bg(CARD, 16)); poseThumb.setClipToOutline(true);
            pr.addView(poseThumb, new LinearLayout.LayoutParams(dp(64), dp(64)));
            poseInfo = text("", 13, DIM, false); poseInfo.setPadding(dp(12), 0, dp(8), 0); pr.addView(poseInfo, new LinearLayout.LayoutParams(0, -2, 1));
            Button ppick = button(L.s("Выбрать", "Choose"), CARD, TXT); ppick.setOnClickListener(v -> posePick(false));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, dp(44)); pp.leftMargin = dp(8); pr.addView(ppick, pp);
            poseDrop = button("✕", CARD, TXT); poseDrop.setContentDescription(L.s("Quitar la pose", "Remove the pose")); poseDrop.setOnClickListener(v -> { Pose.clear(this); refreshPose(); });
            LinearLayout.LayoutParams pd = new LinearLayout.LayoutParams(dp(48), dp(44)); pd.leftMargin = dp(8); pr.addView(poseDrop, pd);
            pb.addView(pr);
            Button plast = button(L.s("↳ Pose de la última imagen", "↳ Pose of the last picture"), FIELD, ACC); plast.setTextSize(12); plast.setAllCaps(false); plast.setOnClickListener(v -> posePick(true));
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(38)); ll.topMargin = dp(6); pb.addView(plast, ll);
            poseStrRow = new LinearLayout(this); poseStrRow.setGravity(Gravity.CENTER_VERTICAL); poseStrRow.setPadding(0, dp(8), 0, 0);
            TextView psl = text(L.s("Fuerza de la pose", "Pose strength"), 13, TXT, false); poseStrRow.addView(psl);
            psl.setOnLongClickListener(v -> { boolean n = !prefs().getBoolean("pose_norm", true); prefs().edit().putBoolean("pose_norm", n).apply();   // test switch: how the control latent is scaled
                Toast.makeText(this, n ? "Pose latent: normalised" : "Pose latent: raw (as ComfyUI)", Toast.LENGTH_SHORT).show(); return true; });
            SeekBar ps = new SeekBar(this); ps.setMax(40); ps.setProgressTintList(ColorStateList.valueOf(ACC)); ps.setThumbTintList(ColorStateList.valueOf(ACC));
            poseStrRow.addView(ps, new LinearLayout.LayoutParams(0, -2, 1));
            final TextView pv = text("", 13, ACC, true); poseStrRow.addView(pv, new LinearLayout.LayoutParams(dp(52), -2));
            ps.setProgress(Math.round((Pose.strength(this) - 0.5f) / 0.05f)); pv.setText(String.format(java.util.Locale.US, "%.2f", Pose.strength(this)));
            ps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar sb, int p, boolean u) { float v = 0.5f + p * 0.05f; pv.setText(String.format(java.util.Locale.US, "%.2f", v)); if (u) { prefs().edit().putFloat("pose_strength", v).apply(); if (poseFold != null) poseFold.sum.setText(L.f("activa · %.2f", "on · %.2f", v)); } }
                public void onStartTrackingTouch(SeekBar sb) { } public void onStopTrackingTouch(SeekBar sb) { } });
            pb.addView(poseStrRow);
            TextView pn = text(L.s("Copia la postura de una persona (una sola, de cuerpo visible) sin copiar su aspecto. Hecho para Anima Base 1.0; con otros modelos Anima puede seguirla peor. Adaptador de Claquasse, uso no comercial.", "Copies the posture of a person (one, with the body in view) without copying the look. Made for Anima Base 1.0; other Anima models may follow it less. Adapter by Claquasse, non-commercial use."), 11, DIM, false);
            pn.setPadding(dp(4), dp(2), dp(4), 0); pb.addView(pn);
        }
        animaInitBox.setVisibility(View.GONE);
    }
    ImageView poseThumb; TextView poseInfo; Button poseDrop; LinearLayout poseStrRow; Fold initFold, poseFold;
    static final int REQ_POSE_PICK = 33;
    /** the pose files are downloaded once (asks first); then the photo picker, or the last picture */
    void posePick(final boolean last) {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        if (Pose.installed(this)) { if (last) poseFromLast(); else pickImage(REQ_POSE_PICK); return; }
        builder().setTitle(L.s("Control de pose", "Pose control"))
            .setMessage(L.s("Copia la pose de una imagen en las imágenes de Anima. Usa un detector de pose y el adaptador «Anima Control – Pose» de Claquasse (no comercial) en la NPU. Hay que descargarlo una vez: 227 MB.",
                "Copies the pose of a picture into Anima pictures. It uses a pose detector and the adapter \"Anima Control – Pose\" by Claquasse (non-commercial) on the NPU. It is downloaded once: 227 MB."))
            .setNegativeButton(L.s("Отмена", "Cancel"), null)
            .setPositiveButton(L.s("Descargar (227 MB)", "Download (227 MB)"), (x, y) -> {
                final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
                final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000);
                final TextView pt = text("", 12, DIM, false);
                LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(12), dp(20), 0); v.addView(pb); v.addView(pt);
                final AlertDialog dl = builder().setTitle(L.s("Загрузка", "Downloading")).setView(v).setCancelable(false).setNegativeButton(L.s("Отмена", "Cancel"), (a, b) -> stop.set(true)).show();
                ex2.submit(() -> {
                    try {
                        Pose.install(this, (done, total) -> ui.post(() -> { if (total > 0) pb.setProgress((int) (1000 * done / total)); pt.setText(String.format(java.util.Locale.US, "%.0f / %.0f MB", done / 1048576.0, total / 1048576.0)); }), stop);
                        ui.post(() -> { dl.dismiss(); if (last) poseFromLast(); else pickImage(REQ_POSE_PICK); });
                    } catch (Exception e) { ui.post(() -> { dl.dismiss(); if (!stop.get()) builder().setMessage(L.f("No se pudo descargar: %s", "Could not download: %s", String.valueOf(e.getMessage()))).setPositiveButton("OK", null).show(); }); }
                });
            }).show();
    }
    void poseFromLast() {
        File src = lastFile;
        if (src != null && (Gallery.meta(src).has("grid") || Gallery.meta(src).optBoolean("compare"))) src = batchFiles.isEmpty() ? null : batchFiles.get(batchFiles.size() - 1);
        if (src == null || !src.exists()) { Toast.makeText(this, L.s("Сначала создайте картинку", "Make a picture first"), Toast.LENGTH_SHORT).show(); return; }
        poseDetect(src, false);
    }
    /** runs the detector on a picture file (deleted afterwards when it is a temporary copy) and shows the skeleton */
    void poseDetect(final File pic, final boolean temp) {
        final Toast t0 = Toast.makeText(this, L.s("Buscando la pose…", "Finding the pose…"), Toast.LENGTH_SHORT); t0.show();
        ex2.submit(() -> {
            try {
                Bitmap b = BitmapFactory.decodeFile(pic.getPath()); if (temp) pic.delete();
                if (b == null) throw new IOException(L.s("No se pudo abrir la imagen", "Could not open the picture"));
                final int n = Pose.detect(this, b); b.recycle();
                ui.post(() -> { refreshPose(); Toast.makeText(this, n == 0 ? L.s("No encontré a nadie: usa una imagen con una persona de cuerpo visible", "Nobody found: use a picture with one person and the body in view") : L.f("Pose encontrada (%d de 17 puntos del cuerpo)", "Pose found (%d of 17 body points)", n), Toast.LENGTH_LONG).show(); });
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("No se pudo detectar: %s", "Detection failed: %s", String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show()); }
        });
    }
    void refreshPose() {
        if (poseThumb == null) return;
        boolean on = Pose.active(this); Bitmap tb = on ? Pose.render(this, 192, 256) : null;
        poseThumb.setImageBitmap(tb); poseThumb.setVisibility(tb != null ? View.VISIBLE : View.GONE);
        poseDrop.setVisibility(on ? View.VISIBLE : View.GONE); poseStrRow.setVisibility(on ? View.VISIBLE : View.GONE);
        if (poseFold != null) { poseFold.sum.setText(on ? L.f("activa · %.2f", "on · %.2f", Pose.strength(this)) : L.s("ninguna", "none")); poseFold.sum.setTextColor(on ? ACC : DIM); }
        poseInfo.setText(on ? L.s("Pose activa: se aplica a cada imagen de Anima.", "Pose on: it is applied to every Anima picture.") : L.s("Elige una imagen para copiar su pose.", "Pick a picture to copy its pose."));
    }
    void refreshAnimaInit() {
        if (animaInitBox == null) return;
        boolean anima = !(isMoss() || isQwen() || isMage() || model.isEmpty());
        animaInitBox.setVisibility(anima ? View.VISIBLE : View.GONE);
        boolean has = initSrc().exists();
        Bitmap tb = null;
        if (has) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 4; tb = BitmapFactory.decodeFile(initSrc().getPath(), o); }
        initThumb.setImageBitmap(tb); initThumb.setVisibility(has ? View.VISIBLE : View.GONE);
        initDrop.setVisibility(has ? View.VISIBLE : View.GONE); initMaskB.setEnabled(has); initMaskB.setAlpha(has ? 1f : .45f);
        initMaskB.setText(maskSrc().exists() ? L.s("✎ Zona pintada · editar", "✎ Painted area · edit") : L.s("✎ Zona a cambiar", "✎ Area to change"));
        ((View) initStr.getParent()).setVisibility(has ? View.VISIBLE : View.GONE);
        refreshAnimaInfo(); refreshPose();
    }
    void refreshAnimaInfo() {
        if (initInfo == null) return;
        if (initFold != null) { boolean has = initSrc().exists(); initFold.sum.setText(has ? L.f("foto · cambia %d %%", "photo · changes %d %%", Math.round(pf("x_strength", .6f) * 100)) + (maskSrc().exists() ? L.s(" · zona", " · area") : "") : L.s("ninguna", "none")); initFold.sum.setTextColor(has ? ACC : DIM); }
        if (!initSrc().exists()) { initInfo.setText(L.s("Elige una foto para cambiarla con el prompt (img2img). Sin foto, el modelo dibuja desde cero.", "Pick a photo to change it by the prompt (img2img). Without one the model draws from scratch.")); return; }
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(initSrc().getPath(), o);
        initInfo.setText(L.f("Foto %d×%d · cambia %d %%", "Photo %d×%d · changes %d %%", o.outWidth, o.outHeight, Math.round(pf("x_strength", .6f) * 100))
            + (maskSrc().exists() ? L.s("\nSolo se redibuja la zona pintada.", "\nOnly the painted area is redrawn.") : ""));
    }
    /** a reference picture's options: keep only its pose as a silhouette (its clothes and face no longer leak into the edit), or back to the picture */
    void refMenu(final File rf) {
        final File orig = new File(rf.getPath() + ".orig");
        java.util.List<String> it = new java.util.ArrayList<>();
        it.add(orig.isFile() ? L.s("Volver a la imagen completa", "Back to the whole picture") : L.s("Usar solo su pose (silueta)", "Use only its pose (silhouette)"));
        builder().setTitle(L.s("Imagen de referencia", "Reference picture")).setItems(it.toArray(new String[0]), (d, k) -> {
            if (orig.isFile()) { rf.delete(); orig.renameTo(rf); refreshEdit(); return; }
            Bitmap b = BitmapFactory.decodeFile(rf.getPath()); if (b == null) return;
            Bitmap s = Qwen.silhouette(b); b.recycle();
            if (s == null) { Toast.makeText(this, L.s("No se distingue la figura: usa una imagen con el fondo liso", "The figure does not stand out: use a picture with a plain background"), Toast.LENGTH_LONG).show(); return; }
            try { rf.renameTo(orig); try (FileOutputStream o = new FileOutputStream(rf)) { s.compress(Bitmap.CompressFormat.PNG, 100, o); } } catch (Exception e) { orig.renameTo(rf); }
            s.recycle(); refreshEdit();
            Toast.makeText(this, L.s("Ahora es una silueta: escribe, por ejemplo, «el personaje de la imagen 1 en la pose de la imagen 2»", "Now it is a silhouette: write, for example, \"the character from image 1 in the pose of image 2\""), Toast.LENGTH_LONG).show();
        }).setNegativeButton(L.s("Отмена", "Cancel"), null).show();
    }
    /** "copy its outfit / pose": the tagger reads reference picture 2, the prompt describes its clothes or pose, and the reference is dropped so photo 1
     *  is edited alone at full size (two pictures share the NPU's attention memory: smaller, mixed results) */
    void copyFromRef(final boolean clothes) {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la tanda", "Wait for the batch to finish"), Toast.LENGTH_SHORT).show(); return; }
        if (!Tagger.installed(this)) { Toast.makeText(this, L.s("Primero descarga «Prompt desde una imagen» (menú ⋯): es lo que lee la imagen 2.", "First download \"Prompt from a picture\" (⋯ menu): it is what reads image 2."), Toast.LENGTH_LONG).show(); return; }
        java.util.List<File> refs = Qwen.extraRefs(getFilesDir()); if (refs.isEmpty()) return;
        final File rf = refs.get(0); final File src = new File(rf.getPath() + ".orig").isFile() ? new File(rf.getPath() + ".orig") : rf;
        final Toast t0 = Toast.makeText(this, L.s("Leyendo la imagen 2…", "Reading image 2…"), Toast.LENGTH_SHORT); t0.show();
        ex2.submit(() -> {
            try {
                Bitmap b = BitmapFactory.decodeFile(src.getPath()); if (b == null) throw new IOException(L.s("No se pudo abrir la imagen 2", "Could not open image 2"));
                java.util.List<Tagger.Tag> tags = Tagger.tag(this, b, 0.25f); b.recycle();
                final java.util.List<String> got = Tagger.pick(tags, clothes, clothes ? 14 : 8);
                ui.post(() -> {
                    if (got.isEmpty()) { Toast.makeText(this, clothes ? L.s("No encontré ropa en la imagen 2", "No clothes found in image 2") : L.s("No encontré una pose clara en la imagen 2", "No clear pose found in image 2"), Toast.LENGTH_LONG).show(); return; }
                    String list = android.text.TextUtils.join(", ", got);
                    String p = clothes ? "Change only the outfit of the person in the picture to: " + list + ". Keep the face, hair, body, pose and background exactly the same."
                        : "Change only the pose of the person in the picture to: " + list + ". Keep the face, hair, outfit and background exactly the same, with natural anatomy and hands.";
                    prompt.setText(p); prompt.setSelection(p.length());
                    for (File x : Qwen.extraRefs(getFilesDir())) { x.delete(); new File(x.getPath() + ".orig").delete(); }   // photo 1 alone, at full size
                    refreshEdit();
                    Toast.makeText(this, clothes ? L.s("Listo. Para no tocar la cara ni el fondo, pinta la ropa con «✎ Zona a cambiar» y genera.", "Done. To leave the face and background alone, paint the clothes with \"✎ Area to edit\" and generate.")
                        : L.s("Listo: genera para cambiar la pose. Puedes retocar el prompt.", "Done: generate to change the pose. You can touch up the prompt."), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("No se pudo leer la imagen 2: %s", "Could not read image 2: %s", String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show()); }
        });
    }
    void useResultAsInit() {
        File src = lastFile;
        if (src != null && (Gallery.meta(src).has("grid") || Gallery.meta(src).optBoolean("compare"))) src = batchFiles.isEmpty() ? null : batchFiles.get(batchFiles.size() - 1);   // a contact sheet: its last picture
        if (src == null || !src.exists()) { Toast.makeText(this, L.s("Сначала создайте картинку", "Make a picture first"), Toast.LENGTH_SHORT).show(); return; }
        try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(initSrc())) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
        catch (IOException e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); return; }
        maskSrc().delete(); refreshExtras();
    }
    void paintMask() {
        if (!initSrc().exists()) { Toast.makeText(this, L.s("Сначала выберите исходную картинку", "Choose an init image first"), Toast.LENGTH_SHORT).show(); return; }
        Bitmap b0 = BitmapFactory.decodeFile(initSrc().getPath()); if (b0 == null) return;
        float s = Math.min(1f, 1024f / Math.max(b0.getWidth(), b0.getHeight()));
        Bitmap base = s < 1 ? Bitmap.createScaledBitmap(b0, Math.round(b0.getWidth() * s), Math.round(b0.getHeight() * s), true) : b0;
        showMaskEditor(base, maskSrc(), L.s("Маска (inpainting)", "Mask (inpainting)"), L.s("Закрасьте то, что нужно перерисовать", "Paint over what should be redrawn"), this::refreshExtras, editBm);
    }
    void paintQwenMask() {
        if (editBm == null) { Toast.makeText(this, L.s("Сначала выберите исходную картинку", "Choose a source photo first"), Toast.LENGTH_SHORT).show(); return; }
        float s = Math.min(1f, 1024f / Math.max(editBm.getWidth(), editBm.getHeight()));
        Bitmap base = s < 1 ? Bitmap.createScaledBitmap(editBm, Math.round(editBm.getWidth() * s), Math.round(editBm.getHeight() * s), true) : editBm;
        showMaskEditor(base, Qwen.editMask(getFilesDir()), isMage() ? L.s("Máscara de Mage", "Mage edit mask") : L.s("Маска Qwen", "Qwen edit mask"),
            L.s("Закрасьте только ту область, которую хотите изменить. Остальная фотография останется исходной.", "Paint only the area you want to change. The rest of the photo will stay original."), this::refreshEdit, editBm);
    }
    // ---- extras: hires fix, ADetailer (the CPU upscaler was removed: it took the CPU to 90 C and the phone hung), init image (img2img), presets, queue ----
    File initSrc() { return new File(getFilesDir(), "init_src.png"); }
    // ---- inpainting: a mask painted over the init image (white = redraw); kept in the init image's own pixels and fitted like it ----
    File maskSrc() { return new File(getFilesDir(), "mask_src.png"); }
    void buildExtras(LinearLayout root) {
        extrasBox = new LinearLayout(this); extrasBox.setOrientation(LinearLayout.VERTICAL);
        extFold = fold(extrasBox, L.s("ДОПОЛНИТЕЛЬНО", "EXTRAS"), "extras");
        LinearLayout c = card(extFold.body); c.setPadding(dp(14), dp(6), dp(14), dp(12));
        sw(c, L.s("Vista previa por pasos de Anima", "Anima live preview"), L.s("Actualiza la imagen mientras se genera con modelos Anima", "Updates the image while Anima models are generating"), "x_preview", true);
        sw(c, "Hires fix", L.s("Второй проход в большем размере (дольше)", "A second pass at a larger size (slower)"), "x_hires", false);
        LinearLayout hr = new LinearLayout(this);
        hsEdit = smallField(hr, L.s("МАСШТАБ", "SCALE"), "x_hires_scale", "1.5", true);
        hdEdit = smallField(hr, "DENOISE", "x_hires_den", "0.45", true);
        hnEdit = smallField(hr, L.s("ШАГИ", "STEPS"), "x_hires_steps", "4", false);
        c.addView(hr);
        hsEdit.setOnFocusChangeListener((v, has) -> { if (!has) clampHiresScale(); });
        hiresAuto = new CheckBox(this);
        hiresAuto.setText(Tr.s(this, "Auto: biggest size within 6144 blocks", "自动锁定为当前最优支持尺寸（6144 块内最大）"));
        hiresAuto.setTextSize(11);
        hiresAuto.setTextColor(DIM);
        hiresAuto.setChecked(prefs().getBoolean("x_hires_auto", false));
        hiresAuto.setOnCheckedChangeListener((v, on) -> { prefs().edit().putBoolean("x_hires_auto", on).apply(); syncHiresAuto(); });
        c.addView(hiresAuto);
        hiresBig = new CheckBox(this);
        hiresBig.setText(Tr.s(this, "Allow above 6144 blocks (slower, may blur)", "允许超过 6144 块（更慢、可能糊）"));
        hiresBig.setTextSize(11);
        hiresBig.setTextColor(DIM);
        hiresBig.setChecked(prefs().getBoolean("x_hires_big_ok", false));
        hiresBig.setOnCheckedChangeListener((v, on) -> prefs().edit().putBoolean("x_hires_big_ok", on).apply());
        c.addView(hiresBig);
        hiresHint = text("", 11, DIM, false);
        hiresHint.setPadding(dp(4), dp(6), 0, 0);
        c.addView(hiresHint);
        updateHiresHint();
        // mod: take a picture that already exists and run the hires second pass on it (no first pass, no wasted generation)
        LinearLayout hb = new LinearLayout(this); hb.setPadding(0, dp(8), 0, 0);
        Button hgal = button(Tr.s(this, "From the album", "从相册导入"), CARD, ACC); hgal.setTextSize(12); hgal.setAllCaps(false);
        hgal.setOnClickListener(v -> hiresPick(false));
        hb.addView(hgal, new LinearLayout.LayoutParams(0, dp(38), 1));
        Button hhist = button(Tr.s(this, "From the history", "从历史导入"), CARD, ACC); hhist.setTextSize(12); hhist.setAllCaps(false);
        hhist.setOnClickListener(v -> hiresPick(true));
        LinearLayout.LayoutParams hhl = new LinearLayout.LayoutParams(0, dp(38), 1); hhl.leftMargin = dp(6); hb.addView(hhist, hhl);
        c.addView(hb); hiresRow = hb;
        TextView hnote = text(Tr.s(this, "Runs the hires fix second pass on a picture you already have, at the scale above: decoded, enlarged and redrawn without generating it again.",
            "对现成的图片直接跑 hires fix 的第二遍（按上面的放大倍率）：解码、放大、重绘，不用重新生成。"), 11, DIM, false);
        hnote.setPadding(dp(4), dp(6), 0, 0); c.addView(hnote);
        sw(c, L.s("ADetailer (лица)", "ADetailer (faces)"), L.s("Находит лица и перерисовывает их детальнее", "Finds faces and redraws them in more detail"), "x_ad", false);
        sw(c, L.s("ADetailer (manos)", "ADetailer (hands)"), L.s("Encuentra las manos y las redibuja con más detalle, con tu mismo modelo (descarga de 6 MB la primera vez)", "Finds the hands and redraws them in more detail with your own model (6 MB download the first time)"), "x_ad_hands", false);
        LinearLayout ar = new LinearLayout(this); ar.setGravity(Gravity.CENTER_VERTICAL);
        adText = text("", 12, DIM, false); ar.addView(adText, new LinearLayout.LayoutParams(0, -2, 1));
        Button adPick = button(L.s("Детектор…", "Detector…"), FIELD, ACC); adPick.setTextSize(12);
        adPick.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 12));
        ar.addView(adPick, new LinearLayout.LayoutParams(-2, dp(36))); c.addView(ar);
        LinearLayout pr = new LinearLayout(this); pr.setPadding(0, dp(10), 0, 0);
        Button presets = button(L.s("Пресеты", "Presets"), FIELD, ACC); presets.setTextSize(13); presets.setOnClickListener(v -> presets());
        pr.addView(presets, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button qa = button(L.s("+ В очередь", "+ Queue"), FIELD, ACC); qa.setTextSize(13); qa.setOnClickListener(v -> enqueue());
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, dp(40), 1); ql.leftMargin = dp(8); pr.addView(qa, ql);
        Button cmp = button("⊞", FIELD, ACC); cmp.setOnClickListener(v -> compare());
        LinearLayout.LayoutParams cml = new LinearLayout.LayoutParams(dp(48), dp(48)); cml.leftMargin = dp(8); pr.addView(cmp, cml);
        Button qr = button("▶", FIELD, ACC); qr.setOnClickListener(v -> runQueue());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(48), dp(48)); rl.leftMargin = dp(8); pr.addView(qr, rl);
        c.addView(pr);
        queueText = text("", 12, DIM, false); queueText.setPadding(dp(2), dp(6), 0, 0); queueText.setOnClickListener(v -> showQueue()); c.addView(queueText);
        root.addView(extrasBox);
        try { JSONArray a = new JSONArray(prefs().getString("queue", "[]")); for (int i = 0; i < a.length(); i++) queue.add(a.getJSONObject(i)); } catch (Exception ignored) { }
        refreshExtras();
    }
    void refreshExtras() {
        if (extrasBox == null) return;
        extrasBox.setVisibility(isMoss() || isQwen() || isMage() || model.isEmpty() ? View.GONE : View.VISIBLE);
        List<String> on = new ArrayList<>();
        if (prefs().getBoolean("x_hires", false)) on.add("Hires"); if (prefs().getBoolean("x_ad", false)) on.add("ADetailer"); if (prefs().getBoolean("x_ad_hands", false)) on.add(L.s("Manos", "Hands"));
        if (initSrc().exists()) on.add(maskSrc().exists() ? "inpaint" : "img2img"); if (!queue.isEmpty()) on.add(L.f("очередь %d", "queue %d", queue.size()));
        extFold.sum.setText(on.isEmpty() ? L.s("выкл.", "off") : android.text.TextUtils.join(" · ", on)); extFold.sum.setTextColor(on.isEmpty() ? DIM : ACC);
        refreshAnimaInit();
        if (false) initText.setText(!initSrc().exists() ? L.s("нет: обычная генерация", "none: normal generation") : maskSrc().exists() ? L.s("✓ выбрана · маска: перерисовать закрашенное", "✓ chosen · mask: redraw the painted part") : L.s("✓ выбрана (подгоняется под размер)", "✓ chosen (fitted to the size)"));
        File ad = adModel();
        adText.setText(ad != null ? "✓ " + ad.getName() : L.s("face_yolov8n: загрузится сам (6 МБ)", "face_yolov8n: downloads on first use (6 MB)"));
        queueText.setText(queue.isEmpty() ? L.s("Очередь пуста", "Queue is empty") : L.f("В очереди: %d (нажмите, чтобы посмотреть)", "Queued: %d (tap to see)", queue.size()));
    }
    File adModel() { File[] fs = adDir().listFiles(); if (fs != null) for (File f : fs) if (f.getName().endsWith(".safetensors")) return f; return null; }
    File adDir() { return new File(getFilesDir(), "adetailer"); }
    Switch sw(LinearLayout parent, String title, String sub, String key, boolean def) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(2), dp(8), dp(2), dp(4));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 15, TXT, true)); if (!sub.isEmpty()) { TextView s = text(sub, 12, DIM, false); t.addView(s); }
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this); s.setChecked(prefs().getBoolean(key, def)); s.setOnCheckedChangeListener((v, on) -> { prefs().edit().putBoolean(key, on).apply(); refreshExtras(); });
        r.addView(s); r.setOnClickListener(v -> s.toggle()); parent.addView(r);
        return s;
    }
    /** 「自动锁定」勾上时：倍率框禁用，值 = 当前分辨率下 6144 块内能放大的最大倍率；分辨率一变就重算 */
    void syncHiresAuto() {
        if (hiresAuto == null || hsEdit == null) return;
        boolean on = hiresAuto.isChecked();
        hsEdit.setEnabled(!on);
        if (!on || resW < 256 || resH < 256) return;
        int[] best = hiresMax(resW, resH, 6144);
        if (best == null) return;
        float f = Math.max(best[0] / (float) resW, best[1] / (float) resH);
        String v = String.format(java.util.Locale.US, "%.2f", f);
        if (!v.equals(hsEdit.getText().toString())) {
            hsEdit.setText(v);
            prefs().edit().putString("x_hires_scale", v).apply();
        }
    }

    /** 倍率输入框：1.0–2.0，超过就改回 2.0（只限制最大放大倍数） */
    void clampHiresScale() {
        try {
            float f = Float.parseFloat(hsEdit.getText().toString().trim().replace(',', '.'));
            float c = Math.max(1.0f, Math.min(2.0f, f));
            if (Math.abs(c - f) > 1e-4) {
                String v = String.format(java.util.Locale.US, "%.2f", c);
                hsEdit.setText(v);
                prefs().edit().putString("x_hires_scale", v).apply();
                Toast.makeText(this, Tr.s(this, "Hires scale is capped at 2x", "放大倍率最大 2 倍"), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) { }
    }

    /** hires 卡片里的两行说明：当前尺寸下建议的最大放大（6144 块）和硬上限（65536 块） */
    void updateHiresHint() {
        if (hiresHint == null) return;
        int w = resW, h = resH;
        if (w < 256 || h < 256) { hiresHint.setText(""); return; }
        int[] rec = hiresMax(w, h, 6144), hard = hiresMax(w, h, 65536);
        String rs = rec == null ? "—" : String.format(java.util.Locale.US, "%dx%d (%.2fx)", rec[0], rec[1], Math.max(rec[0] / (double) w, rec[1] / (double) h));
        String hs2 = hard == null ? "—" : String.format(java.util.Locale.US, "%dx%d (%.2fx)", hard[0], hard[1], Math.max(hard[0] / (double) w, hard[1] / (double) h));
        hiresHint.setText(Tr.f(this, "Now %1$dx%2$d · recommended max %3$s (6144 blocks, normal speed) · hard max %4$s (slower above 6144)",
                "当前 %1$dx%2$d · 建议最大 %3$s（6144 块，正常速度）· 硬上限 %4$s（超过 6144 会明显变慢）", w, h, rs, hs2));
    }

    EditText smallField(LinearLayout row, String title, String key, String def, boolean decimal) {
        return numField(row, title, prefs().getString(key, def), decimal, s -> { String v = s.trim().replace(',', '.'); try { Float.parseFloat(v); prefs().edit().putString(key, v).apply(); } catch (NumberFormatException ignored) { } });
    }
    float pf(String key, float def) { try { return Float.parseFloat(prefs().getString(key, String.valueOf(def))); } catch (Exception e) { return def; } }

    // ---- mod: Hires fix redo — the second pass of the hires fix on a picture that already exists (album or History) ------------------
    /** the two import buttons: the system photo picker, or a grid of the app's own History */
    void hiresPick(final boolean history) {
        if (!animaReady()) return;
        if (history) hiresHistoryDialog(); else pickImage(REQ_HIRES_PICK);
    }
    boolean animaReady() {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return false; }
        if (isMoss() || isQwen() || isMage() || model.isEmpty()) { Toast.makeText(this, Tr.s(this, "The hires fix works with Anima models only", "Hires fix 只对 Anima 模型有效"), Toast.LENGTH_LONG).show(); return false; }
        return true;
    }
    /** the last 30 pictures of the app's own History, as a 3-column grid */
    void hiresHistoryDialog() {
        final List<File> pics = new ArrayList<>();   // contact sheets and before/after strips are not pictures: skip them
        for (File f : Gallery.list(this, Gallery.ALL)) { JSONObject m = Gallery.meta(f); if (m.has("grid") || m.optBoolean("compare")) continue; pics.add(f); if (pics.size() >= 30) break; }
        if (pics.isEmpty()) { Toast.makeText(this, Tr.s(this, "No pictures in the History yet", "历史里还没有图片"), Toast.LENGTH_LONG).show(); return; }
        final int n = pics.size(), gap = dp(3);
        final int cell = (getResources().getDisplayMetrics().widthPixels - dp(64)) / 3, cellH = (int) (cell * 1.3f);
        final LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL); grid.setPadding(gap, gap, gap, gap);
        final java.util.List<ImageView> slots = new ArrayList<>(); final java.util.List<File> files = new ArrayList<>();
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < n; i += 3) {
            LinearLayout row = new LinearLayout(this);
            for (int k = i; k < Math.min(n, i + 3); k++) {
                final File f = pics.get(k);
                ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackground(bg(CARD, 14)); iv.setClipToOutline(true);
                iv.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); hiresRedoDialog(f, Gallery.meta(f)); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cellH); lp.setMargins(gap, gap, gap, gap); row.addView(iv, lp);
                slots.add(iv); files.add(f);
            }
            grid.addView(row);
        }
        ScrollView sv = new ScrollView(this); sv.addView(grid);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(8), dp(8), dp(8), 0);
        box.addView(sv, new LinearLayout.LayoutParams(-1, dp(430)));
        dlg[0] = builder().setTitle(Tr.s(this, "Hires fix: pick a picture from the History", "Hires fix 放大重绘 · 从历史选图")).setView(box)
            .setNegativeButton(Tr.s(this, "Cancel", "取消"), null).show();
        new Thread(() -> {   // thumbnails, smallest first: one picture at a time so the dialog appears at once
            for (int i = 0; i < files.size(); i++) { final Bitmap t = hiresThumb(files.get(i), cell, cellH); if (t == null) continue; final int k = i; ui.post(() -> slots.get(k).setImageBitmap(t)); }
        }).start();
    }
    static Bitmap hiresThumb(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1; while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o2); if (b == null) return null;
        Bitmap t = Bitmap.createScaledBitmap(b, w, h, true); if (t != b) b.recycle();
        return t;
    }
    /** target of the redo: the picture × `pre` (the "compress first" slider) × the SCALE above, sides multiples of 16, a patch count the
     *  NPU runs, long side at most 2048 (the first valid size from that scale downwards, so a big picture is fitted instead of refused) */
    int[] hiresTargetFor(int w, int h, double pre) {
        final double s0 = Math.max(1.0, Math.min(3.0, pf("x_hires_scale", 1.5f))) * Math.max(0.1, Math.min(1.0, pre));
        for (double s = s0; s >= 0.1; s -= 0.05) {
            int w2 = (int) Math.round(w * s / 16) * 16, h2 = (int) Math.round(h * s / 16) * 16;
            if (w2 < 256 || h2 < 256) break;
            if (Math.max(w2, h2) > 2048) continue;
            if (sizeError(w2, h2) != null) continue;
            return new int[]{w2, h2};
        }
        return null;
    }
    /** the confirm sheet: what it will do, the prompt (the picture's own when it has one) and the "read the prompt from the picture" button */
    void hiresRedoDialog(final File src, final JSONObject meta) {
        if (!animaReady()) return;
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(src.getPath(), o);
        final int sw = o.outWidth, sh = o.outHeight;
        if (sw <= 0 || sh <= 0) { Toast.makeText(this, L.s("No se pudo abrir la imagen", "Could not open the picture"), Toast.LENGTH_LONG).show(); return; }
        final int[] natural = hiresTargetFor(sw, sh, 1.0);
        if (natural == null) { Toast.makeText(this, Tr.s(this, "This picture is too large or too small for the hires fix", "这张图太大或太小，无法做 hires 重绘"), Toast.LENGTH_LONG).show(); return; }
        final float den = Math.max(.05f, Math.min(.95f, pf("x_hires_den", .45f)));
        final int hs = Math.max(1, (int) pf("x_hires_steps", 4));
        final String own = meta != null ? meta.optString("prompt", "").trim() : "";
        // a picture that would be redrawn over ~1152 on its long side starts compressed, at a ratio that lands just under it
        double defPre = 1.0;
        if (Math.max(natural[0], natural[1]) > 1152) defPre = Math.max(.3, Math.min(1.0, Math.round(1152.0 / Math.max(natural[0], natural[1]) / .05) * .05));
        final int[] cur = new int[2];
        final LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(6), dp(18), dp(4));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackground(bg(CARD, 14)); iv.setClipToOutline(true);
        iv.setImageBitmap(hiresThumb(src, dp(58), dp(58)));
        top.addView(iv, new LinearLayout.LayoutParams(dp(58), dp(58)));
        final TextView info = text("", 12, DIM, false);
        info.setPadding(dp(12), 0, 0, 0); top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(top);
        // mod: a big album picture ×1.5 does not fit the phone (the init image is VAE-encoded on the CPU first: memory + time grow with the area),
        // so the redo can shrink the picture by a ratio before redrawing it; the target size is shown while the slider moves
        final LinearLayout crow = new LinearLayout(this); crow.setGravity(Gravity.CENTER_VERTICAL); crow.setPadding(0, dp(10), 0, 0);
        crow.addView(text(Tr.s(this, "Shrink before the redraw", "先按比例压缩再重绘"), 13, TXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        final TextView preVal = text("", 13, ACC, true); crow.addView(preVal, new LinearLayout.LayoutParams(dp(56), -2));
        box.addView(crow);
        final Switch preOn = new Switch(this); preOn.setChecked(defPre < 1.0); crow.addView(preOn);
        final SeekBar preBar = new SeekBar(this); preBar.setMax(16);
        preBar.setProgressTintList(ColorStateList.valueOf(ACC)); preBar.setThumbTintList(ColorStateList.valueOf(ACC));
        preBar.setProgress((int) Math.max(0L, Math.min(16L, Math.round((defPre - 0.2f) / 0.05f))));
        box.addView(preBar);
        final TextView warn = text(Tr.s(this, "Large size: much slower and hotter than usual (the picture is encoded on the CPU first).", "尺寸较大：会比平时慢很多、也热很多（这张图要先在 CPU 上编码）。"), 12, 0xFFFFA726, false);
        final LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2); wl.topMargin = dp(6); box.addView(warn, wl);
        final Runnable paint = () -> {
            final boolean on = preOn.isChecked();
            final double p = on ? .2 + preBar.getProgress() * .05 : 1.0;
            preVal.setText(Math.round(p * 100) + " %");
            preBar.setEnabled(on); preBar.setAlpha(on ? 1f : .4f);
            final int[] tg = hiresTargetFor(sw, sh, p);
            cur[0] = tg != null ? tg[0] : 0; cur[1] = tg != null ? tg[1] : 0;
            if (cur[0] == 0) { info.setText(Tr.s(this, "No size fits the NPU at this scale.", "这个倍率下没有可用的尺寸。")); warn.setVisibility(View.GONE); return; }
            final int cw = Math.max(16, (int) Math.round(sw * p / 16) * 16), chh = Math.max(16, (int) Math.round(sh * p / 16) * 16);
            info.setText(sw + "×" + sh + "\n"
                + (on ? Tr.f(this, "shrunk to %d×%d (%d %%)", "压缩 %d×%d（%d%%）", cw, chh, Math.round(p * 100)) : Tr.s(this, "no compression", "不压缩")) + "\n"
                + Tr.f(this, "→ %d×%d · denoise %d %% · %d steps", "→ %d×%d · 重绘 %d%% · %d 步", cur[0], cur[1], Math.round(den * 100), hs));
            warn.setVisibility(Math.max(cur[0], cur[1]) > 1536 ? View.VISIBLE : View.GONE);
        };
        preOn.setOnCheckedChangeListener((v, on) -> paint.run());
        preBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { paint.run(); }
            public void onStartTrackingTouch(SeekBar sb) { } public void onStopTrackingTouch(SeekBar sb) { } });
        paint.run();
        box.addView(text(Tr.s(this, "Prompt", "提示词"), 12, DIM, true));
        final EditText pr = field(Tr.s(this, "Describe the picture…", "描述这张图…")); pr.setTextSize(14); pr.setMinLines(3); pr.setGravity(Gravity.TOP);
        pr.setText(own.isEmpty() ? prompt.getText().toString().trim() : own);
        if (!own.isEmpty()) { TextView src2 = text(Tr.s(this, "From the settings saved with that picture.", "取自这张图保存的设置。"), 11, DIM, false); src2.setPadding(0, dp(2), 0, dp(4)); box.addView(src2); }
        else { TextView src2 = text(Tr.s(this, "No prompt was saved with this picture: write one, or read it from the picture below.", "这张图没有提示词记录：自己写，或用下面的「从图片反推提示词」。"), 11, DIM, false); src2.setPadding(0, dp(2), 0, dp(4)); box.addView(src2); }
        box.addView(pr);
        final Button rev = button(Tr.s(this, "Prompt from the picture", "从图片反推提示词"), FIELD, ACC); rev.setTextSize(12); rev.setAllCaps(false);
        if (Tagger.supported()) { rev.setOnClickListener(v -> tagForRedo(src, pr, rev)); } else rev.setVisibility(View.GONE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(38)); rl.topMargin = dp(8); box.addView(rev, rl);
        final AlertDialog dlg = builder().setTitle(Tr.s(this, "Hires fix: enlarge and redraw", "Hires fix 放大重绘")).setView(box)
            .setPositiveButton(Tr.s(this, "Start", "开始重绘"), null)
            .setNegativeButton(Tr.s(this, "Cancel", "取消"), null).show();
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {   // stays open when the prompt is still empty
            final String p = pr.getText().toString().trim();
            if (p.isEmpty()) { Toast.makeText(this, Tr.s(this, "Write a prompt first (or read it from the picture): the redraw follows it", "先写提示词（或从图片反推）：重绘是按提示词走的"), Toast.LENGTH_LONG).show(); return; }
            if (cur[0] == 0) { Toast.makeText(this, Tr.s(this, "No size fits the NPU at this scale", "这个倍率下没有可用的尺寸"), Toast.LENGTH_LONG).show(); return; }
            dlg.dismiss(); startHiresRedo(src, cur[0], cur[1], p, meta);
        });
    }
    /** "better fidelity": reads the picture with the tagger (the app's own "Prompt from a picture" model) and fills the field */
    void tagForRedo(final File pic, final EditText out, final Button btn) {
        if (Tagger.installed(this)) { runTagger(pic, out, btn); return; }
        builder().setTitle(Tr.s(this, "Prompt from the picture", "从图片反推提示词"))
            .setMessage(Tr.s(this, "Reads the picture and writes its prompt (tags such as 1girl, silver hair…). It uses the same WD ViT Tagger v3 (Apache-2.0) as \"Prompt from a picture\", on the NPU, in under a second. It is downloaded once: 190 MB.",
                "读取图片并写出它的提示词（1girl、silver hair… 这类标签）。用的是和“从图片反推提示词”同一个 WD ViT Tagger v3（Apache-2.0），在 NPU 上跑，1 秒内。需要下载一次：190 MB。"))
            .setNegativeButton(Tr.s(this, "Cancel", "取消"), null)
            .setPositiveButton(Tr.s(this, "Download (190 MB)", "下载（190 MB）"), (x, y) -> {
                final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
                final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000);
                final TextView pt = text("", 12, DIM, false);
                LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(12), dp(20), 0); v.addView(pb); v.addView(pt);
                final AlertDialog dl = builder().setTitle(L.s("Загрузка", "Downloading")).setView(v).setCancelable(false).setNegativeButton(L.s("Отмена", "Cancel"), (a, b) -> stop.set(true)).show();
                ex2.submit(() -> {
                    try {
                        Tagger.install(this, (done, total) -> ui.post(() -> { if (total > 0) pb.setProgress((int) (1000 * done / total)); pt.setText(String.format(java.util.Locale.US, "%.0f / %.0f MB", done / 1048576.0, total / 1048576.0)); }), stop);
                        ui.post(() -> { dl.dismiss(); runTagger(pic, out, btn); });
                    } catch (Exception e) { ui.post(() -> { dl.dismiss(); if (!stop.get()) builder().setMessage(L.f("No se pudo descargar: %s", "Could not download: %s", String.valueOf(e.getMessage()))).setPositiveButton("OK", null).show(); }); }
                });
            }).show();
    }
    void runTagger(final File pic, final EditText out, final Button btn) {
        btn.setEnabled(false); final Toast t0 = Toast.makeText(this, Tr.s(this, "Reading the picture…", "正在读取图片…"), Toast.LENGTH_SHORT); t0.show();
        ex2.submit(() -> {
            try {
                Bitmap b = BitmapFactory.decodeFile(pic.getPath()); if (b == null) throw new IOException(L.s("No se pudo abrir la imagen", "Could not open the picture"));
                java.util.List<Tagger.Tag> tags = Tagger.tag(this, b); b.recycle();
                final String s = Tagger.tags(tags, Tagger.MEDIUM);
                ui.post(() -> { btn.setEnabled(true); out.setText(s); out.setSelection(out.getText().length()); });
            } catch (Exception e) { ui.post(() -> { btn.setEnabled(true); Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show(); }); }
        });
    }
    /** the run itself: the picture becomes the init image of one generation at the target size, with the hires denoise / steps and no second hires pass */
    void startHiresRedo(final File src, final int w2, final int h2, final String pr, final JSONObject meta) {
        if (!animaReady()) return;
        if (pr.isEmpty()) { Toast.makeText(this, Tr.s(this, "Write a prompt first (or read it from the picture): the redraw follows it", "先写提示词（或从图片反推）：重绘是按提示词走的"), Toast.LENGTH_LONG).show(); return; }
        final String fromName = Gallery.dir(this).equals(src.getParentFile()) ? src.getName() : null;   // a History picture: the viewer gets the before/after slider
        ex2.submit(() -> {
            try {
                final File f = new File(getFilesDir(), "hires_src.png");
                try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(f)) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
                ui.post(() -> {
                    final float den = Math.max(.05f, Math.min(.95f, pf("x_hires_den", .45f)));
                    final int hs = Math.max(1, (int) pf("x_hires_steps", 4));
                    pendRedo = f; pendRedoStr = den; pendRedoSteps = Math.max(hs, Math.round(hs / den)); pendRedoName = fromName; armNext = true;
                    redoOldW = resW; redoOldH = resH; redoNewW = w2; redoNewH = h2; redoRestoreRes = true;
                    setRes(w2, h2);
                    prompt.setText(pr); prompt.setSelection(prompt.getText().length());
                    generate(false);
                    armNext = false;
                    if (pendRedo != null) {   // generate() refused before it started (memory, size): nothing armed, give the size back
                        pendRedo = null; f.delete();
                        if (redoRestoreRes) { redoRestoreRes = false; setRes(redoOldW, redoOldH); }
                    }
                });
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("No se pudo preparar la imagen: %s", "Could not prepare the picture: %s", String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show()); }
        });
    }
}

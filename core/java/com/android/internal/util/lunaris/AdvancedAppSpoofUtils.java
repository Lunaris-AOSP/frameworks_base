/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.lunaris;

import android.content.Context;
import android.content.ContentResolver;
import android.os.Build;
import android.provider.Settings;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @hide
 */
public final class AdvancedAppSpoofUtils {

    private static final String TAG = "AdvancedAppSpoofUtils";
    private static final boolean DEBUG = false;

    private static final Map<String, String[]> GPU_PRESETS = new HashMap<>();

    private static final Map<String, String[]> CPU_PRESETS = new HashMap<>();

    private static final int GPU_RENDERER = 0;
    private static final int GPU_VENDOR = 1;
    private static final int GPU_VK_DEVICE = 2;
    private static final int GPU_VK_VENDOR = 3;

    private static final int CPU_HARDWARE = 0;
    private static final int CPU_SOC_MODEL = 1;
    private static final int CPU_SOC_MFR = 2;
    private static final int CPU_BOARD = 3;
    private static final int CPU_TEMPLATE = 4;

    static {
        // ---- GPU Presets ----
        GPU_PRESETS.put("adreno830", new String[]{"Adreno (TM) 830", "Qualcomm", "Adreno (TM) 830", "0x5143"});
        GPU_PRESETS.put("adreno840", new String[]{"Adreno (TM) 840", "Qualcomm", "Adreno (TM) 840", "0x5143"});
        GPU_PRESETS.put("adreno750", new String[]{"Adreno (TM) 750", "Qualcomm", "Adreno (TM) 750", "0x5143"});
        GPU_PRESETS.put("adreno740", new String[]{"Adreno (TM) 740", "Qualcomm", "Adreno (TM) 740", "0x5143"});
        GPU_PRESETS.put("adreno730", new String[]{"Adreno (TM) 730", "Qualcomm", "Adreno (TM) 730", "0x5143"});
        GPU_PRESETS.put("adreno720", new String[]{"Adreno (TM) 720", "Qualcomm", "Adreno (TM) 720", "0x5143"});
        GPU_PRESETS.put("mali_g925", new String[]{"Mali-G925 Immortalis-MC12", "ARM", "Mali-G925 Immortalis-MC12", "0x13B5"});
        GPU_PRESETS.put("mali_g920", new String[]{"Mali-G920 Immortalis-MC12", "ARM", "Mali-G920 Immortalis-MC12", "0x13B5"});
        GPU_PRESETS.put("mali_g720", new String[]{"Mali-G720 Immortalis-MC12", "ARM", "Mali-G720 Immortalis-MC12", "0x13B5"});
        GPU_PRESETS.put("mali_g715", new String[]{"Mali-G715 Immortalis-MC11", "ARM", "Mali-G715 Immortalis-MC11", "0x13B5"});
        GPU_PRESETS.put("maleoon920", new String[]{"Maleoon 920", "HiSilicon", "Maleoon 920", "0x19E5"});
        GPU_PRESETS.put("maleoon910", new String[]{"Maleoon 910", "HiSilicon", "Maleoon 910", "0x19E5"});
        GPU_PRESETS.put("apple_a18pro", new String[]{"Apple A18 Pro GPU", "Apple", "Apple A18 Pro", "0x106B"});

        // Snapdragon 8 Elite (SM8750-AB) - 4+4 Oryon
        CPU_PRESETS.put("sd8elite", new String[]{
            "Qualcomm Technologies, Inc SM8750-AB",
            "SM8750-AB",
            "Qualcomm",
            "kalama",
            "Processor\t: AArch64 Processor rev 4 (aarch64)\n" +
            "processor\t: 0\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x801\nCPU revision\t: 4\n\n" +
            "processor\t: 1\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x801\nCPU revision\t: 4\n\n" +
            "processor\t: 2\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x801\nCPU revision\t: 4\n\n" +
            "processor\t: 3\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x801\nCPU revision\t: 4\n\n" +
            "processor\t: 4\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x800\nCPU revision\t: 2\n\n" +
            "processor\t: 5\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x800\nCPU revision\t: 2\n\n" +
            "processor\t: 6\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x800\nCPU revision\t: 2\n\n" +
            "processor\t: 7\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0xa\nCPU part\t: 0x800\nCPU revision\t: 2\n\n" +
            "Hardware\t: Qualcomm Technologies, Inc SM8750-AB\n"
        });

        // Snapdragon 8 Gen 3 (SM8650-AB)
        CPU_PRESETS.put("sd8gen3", new String[]{
            "Qualcomm Technologies, Inc SM8650-AB",
            "SM8650-AB",
            "Qualcomm",
            "kalama",
            "Processor\t: AArch64 Processor rev 4 (aarch64)\n" +
            "processor\t: 0\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x001\nCPU revision\t: 1\n\n" +
            "processor\t: 1\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x001\nCPU revision\t: 1\n\n" +
            "processor\t: 2\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x001\nCPU revision\t: 1\n\n" +
            "processor\t: 3\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x001\nCPU revision\t: 1\n\n" +
            "processor\t: 4\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x000\nCPU revision\t: 0\n\n" +
            "processor\t: 5\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x000\nCPU revision\t: 0\n\n" +
            "processor\t: 6\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x000\nCPU revision\t: 0\n\n" +
            "processor\t: 7\nBogoMIPS\t: 38.40\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x51\nCPU architecture: 8\n" +
            "CPU variant\t: 0x6\nCPU part\t: 0x000\nCPU revision\t: 0\n\n" +
            "Hardware\t: Qualcomm Technologies, Inc SM8650-AB\n"
        });

        // Dimensity 9400 (MT6989)
        CPU_PRESETS.put("dimensity9400", new String[]{
            "MT6989",
            "MT6989",
            "MediaTek",
            "mt6989",
            "Processor\t: AArch64 Processor rev 4 (aarch64)\n" +
            "processor\t: 0\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x5\nCPU part\t: 0xd8e\nCPU revision\t: 0\n\n" +
            "processor\t: 1\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x4\nCPU part\t: 0xd85\nCPU revision\t: 0\n\n" +
            "processor\t: 2\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x4\nCPU part\t: 0xd85\nCPU revision\t: 0\n\n" +
            "processor\t: 3\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x4\nCPU part\t: 0xd85\nCPU revision\t: 0\n\n" +
            "processor\t: 4\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 5\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 6\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 7\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "Hardware\t: MT6989\n"
        });

        // Kirin 9020
        CPU_PRESETS.put("kirin9020", new String[]{
            "Kirin 9020",
            "Kirin 9020",
            "HiSilicon",
            "hi6985",
            "Processor\t: AArch64 Processor rev 4 (aarch64)\n" +
            "processor\t: 0\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x5\nCPU part\t: 0xd8e\nCPU revision\t: 0\n\n" +
            "processor\t: 1\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x5\nCPU part\t: 0xd8e\nCPU revision\t: 0\n\n" +
            "processor\t: 2\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x4\nCPU part\t: 0xd85\nCPU revision\t: 0\n\n" +
            "processor\t: 3\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x4\nCPU part\t: 0xd85\nCPU revision\t: 0\n\n" +
            "processor\t: 4\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 5\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 6\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 7\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "Hardware\t: Kirin 9020\n"
        });

        // Kirin 9030 Pro
        CPU_PRESETS.put("kirin9030pro", new String[]{
            "Kirin 9030 Pro",
            "Kirin 9030 Pro",
            "HiSilicon",
            "hi6986",
            "Processor\t: AArch64 Processor rev 4 (aarch64)\n" +
            "processor\t: 0\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x5\nCPU part\t: 0xd8e\nCPU revision\t: 0\n\n" +
            "processor\t: 1\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x5\nCPU part\t: 0xd8e\nCPU revision\t: 0\n\n" +
            "processor\t: 2\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x4\nCPU part\t: 0xd85\nCPU revision\t: 0\n\n" +
            "processor\t: 3\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x4\nCPU part\t: 0xd85\nCPU revision\t: 0\n\n" +
            "processor\t: 4\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 5\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 6\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "processor\t: 7\nBogoMIPS\t: 26.00\n" +
            "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n" +
            "CPU implementer\t: 0x41\nCPU architecture: 8\n" +
            "CPU variant\t: 0x2\nCPU part\t: 0xd05\nCPU revision\t: 0\n\n" +
            "Hardware\t: Kirin 9030 Pro\n"
        });
    }

    public static void setProps(Context context) {
        if (context == null) return;

        final String packageName;
        try {
            packageName = context.getPackageName();
        } catch (Exception e) {
            return;
        }
        if (TextUtils.isEmpty(packageName)) return;

        try {
            final ContentResolver cr = context.getContentResolver();
            if (Settings.Secure.getInt(cr, Settings.Secure.ADVANCED_APP_SPOOF_ENABLED, 0) == 0) {
                return;
            }
            final String configJson = Settings.Secure.getString(cr,
                    Settings.Secure.ADVANCED_APP_SPOOF_CONFIG);
            if (TextUtils.isEmpty(configJson)) return;

            applyConfig(context, packageName, configJson);
        } catch (Exception e) {
            dlog("setProps exception for " + packageName + ": " + e.getMessage());
        }
    }

    private static void applyConfig(Context context, String packageName, String configJson) {
        final JSONArray arr;
        try {
            arr = new JSONArray(configJson);
        } catch (JSONException e) {
            dlog("Bad JSON in ADVANCED_APP_SPOOF_CONFIG: " + e.getMessage());
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry;
            try { entry = arr.getJSONObject(i); } catch (JSONException e) { continue; }

            if (!packageName.equals(entry.optString("pkg", ""))) continue;

            final String gpuKey = entry.optString("gpu", "");
            final String cpuKey = entry.optString("cpu", "");

            if (!TextUtils.isEmpty(gpuKey)) {
                try { applyGpuSpoof(gpuKey); }
                catch (Exception e) { dlog("GPU spoof error: " + e.getMessage()); }
            }
            if (!TextUtils.isEmpty(cpuKey)) {
                try { applyCpuSpoof(context, packageName, cpuKey); }
                catch (Exception e) { dlog("CPU spoof error: " + e.getMessage()); }
            }
            return;
        }
    }

    private static void applyGpuSpoof(String gpuKey) throws Exception {
        final String[] p = GPU_PRESETS.get(gpuKey);
        if (p == null) { dlog("Unknown GPU preset: " + gpuKey); return; }

        Os.setenv("SPOOF_GPU_RENDERER", p[GPU_RENDERER], true);
        Os.setenv("SPOOF_GPU_VENDOR", p[GPU_VENDOR], true);
        Os.setenv("SPOOF_VULKAN_DEVICE", p[GPU_VK_DEVICE], true);
        Os.setenv("SPOOF_VULKAN_VENDOR_ID",p[GPU_VK_VENDOR], true);

        dlog("GPU spoof: renderer=" + p[GPU_RENDERER] + " vendor=" + p[GPU_VENDOR]);
    }

    private static void applyCpuSpoof(Context context, String pkg, String cpuKey) throws Exception {
        final String[] p = CPU_PRESETS.get(cpuKey);
        if (p == null) { dlog("Unknown CPU preset: " + cpuKey); return; }

        final File cacheDir = context.getCacheDir();
        if (cacheDir == null) { dlog("getCacheDir null"); return; }

        final File spoofDir = new File(cacheDir, ".advsf");
        if (!spoofDir.exists()) spoofDir.mkdirs();

        final File cpuFile = new File(spoofDir, "cpuinfo_" + cpuKey);
        if (!cpuFile.exists() || cpuFile.length() == 0) {
            try (FileWriter fw = new FileWriter(cpuFile)) {
                fw.write(p[CPU_TEMPLATE]);
            } catch (IOException e) {
                dlog("cpuinfo write fail: " + e.getMessage());
                return;
            }
        }

        Os.setenv("SPOOF_CPUINFO_PATH", cpuFile.getAbsolutePath(), true);

        setBuildField("HARDWARE", p[CPU_HARDWARE]);
        setBuildField("BOARD", p[CPU_BOARD]);
        setBuildField("SOC_MODEL", p[CPU_SOC_MODEL]);
        setBuildField("SOC_MANUFACTURER", p[CPU_SOC_MFR]);

        dlog("CPU spoof: hardware=" + p[CPU_HARDWARE] + " cpuinfo=" + cpuFile.getAbsolutePath());
    }

    private static void setBuildField(String name, String value) {
        if (TextUtils.isEmpty(value)) return;
        try {
            final Field f = Build.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
            f.setAccessible(false);
        } catch (NoSuchFieldException e) {
            dlog("Build." + name + " not found");
        } catch (IllegalAccessException e) {
            Log.w(TAG, "Cannot set Build." + name, e);
        }
    }

    public static Map<String, String> getGpuPresetNames() {
        final Map<String, String> m = new HashMap<>();
        m.put("adreno830", "Adreno 830 · Snapdragon 8 Elite");
        m.put("adreno840", "Adreno 840 · Snapdragon 8 Elite Gen 5");
        m.put("adreno750", "Adreno 750 · Snapdragon 8 Gen 3");
        m.put("adreno740", "Adreno 740 · Snapdragon 8 Gen 2");
        m.put("adreno730", "Adreno 730 · Snapdragon 8 Gen 1");
        m.put("adreno720", "Adreno 720 · Snapdragon 7s Gen 3");
        m.put("mali_g925", "Mali-G925 Immortalis · Dimensity 9400");
        m.put("mali_g920", "Mali-G920 Immortalis · Dimensity 9400+");
        m.put("mali_g720", "Mali-G720 Immortalis · Dimensity 9300");
        m.put("mali_g715", "Mali-G715 Immortalis · Dimensity 9200+");
        m.put("maleoon920", "Maleoon 920 · Kirin 9020");
        m.put("maleoon910", "Maleoon 910 · Kirin 9010");
        m.put("apple_a18pro", "Apple A18 Pro GPU");
        return Collections.unmodifiableMap(m);
    }

    public static Map<String, String> getCpuPresetNames() {
        final Map<String, String> m = new HashMap<>();
        m.put("sd8elite", "Snapdragon 8 Elite (SM8750)");
        m.put("sd8gen3","Snapdragon 8 Gen 3 (SM8650)");
        m.put("dimensity9400", "Dimensity 9400 (MT6989)");
        m.put("kirin9020", "Kirin 9020");
        m.put("kirin9030pro", "Kirin 9030 Pro");
        return Collections.unmodifiableMap(m);
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}

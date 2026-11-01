package android.security.trickystore;

import android.hardware.security.keymint.Tag;
import android.os.Build;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.os.SystemProperties;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.*;

/**
 * @hide
 */
public final class AttestationUtils {
    private static final String TAG = "AttestationUtils";

    private static final File CONFIG_DIR = new File("/data/adb/tricky_store");
    private static final File BOOT_KEY_FILE = new File(CONFIG_DIR, "boot_key");
    private static final File HBK_FILE = new File(CONFIG_DIR, "hbk");

    // Refreshed without acquiring this class's monitor: boot-value resolution can
    // call back into TrickyStoreService while that service replaces its keybox.
    private static volatile Object sKeyboxVersion = new Object();
    private static Object sBootKeyVersion;
    private static Object sBootHashVersion;
    private static boolean sBootKeyFallback;
    private static boolean sBootHashFallback;
    private static byte[] sBootKey;
    private static byte[] sBootHash;
    private static volatile boolean sTeeBroken = false;

    private AttestationUtils() {}

    public static byte[] getModuleHash() {
        try {
            byte[] modulesDer = KeyStore2.getInstance()
                    .getSupplementaryAttestationInfo(Tag.MODULE_HASH);
            if (modulesDer == null || modulesDer.length == 0) {
                Log.w(TAG, "MODULE_HASH supplementary data unavailable");
                return null;
            }
            return MessageDigest.getInstance("SHA-256").digest(modulesDer);
        } catch (KeyStoreException | java.security.NoSuchAlgorithmException e) {
            Log.w(TAG, "Unable to calculate MODULE_HASH from Keystore2", e);
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "MODULE_HASH query failed", e);
            return null;
        }
    }

    public static void setTeeBroken(boolean broken) {
        sTeeBroken = broken;
    }

    public static boolean isTeeBroken() {
        return sTeeBroken;
    }

    public static void invalidateFallbackBootState() {
        sKeyboxVersion = new Object();
    }

    public static synchronized byte[] getBootKey() {
        Object version = sKeyboxVersion;
        if (sBootKey == null || (sBootKeyFallback && sBootKeyVersion != version)) {
            sBootKeyVersion = version;
            sBootKey = getBootKeyFromProp();
            sBootKeyFallback = sBootKey == null;
            if (sBootKey == null) {
                sBootKey = getBootKeyFromKeybox();
            }
            if (sBootKey == null) {
                sBootKey = loadOrCreatePersisted(BOOT_KEY_FILE);
            }
        }
        return sBootKey;
    }

    /**
     * Derives a fallback verified-boot hash from the active keybox's issuer certificate, used
     * ahead of the random fallback so that, when the vbmeta prop is missing, nothing persisted
     * exists and the TEE is broken, the RootOfTrust hash is still deterministic for a given
     * keybox instead of changing on every boot. Domain-separated from the boot KEY digest
     * (a plain SHA-256 of the same cert) so the two fields don't collide.
     * Returns null if no keybox is loaded.
     */
    private static byte[] deriveBootHashFromKeybox() {
        try {
            KeyBoxManager keyBoxManager = TrickyStoreService.getInstance().getKeyBoxManager();
            if (keyBoxManager == null || !keyBoxManager.hasKeyboxes()) {
                return null;
            }
            KeyBoxManager.KeyBox keybox = keyBoxManager.getKeybox("EC");
            if (keybox == null) {
                keybox = keyBoxManager.getKeybox("RSA");
            }
            if (keybox == null || keybox.certificates == null || keybox.certificates.isEmpty()) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("boothash".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return digest.digest(keybox.certificates.get(0).getEncoded());
        } catch (Exception e) {
            Log.w(TAG, "Failed to derive boot hash from keybox", e);
            return null;
        }
    }

    /**
     * Derives a fallback verified-boot key from the active keybox's issuer certificate,
     * so that when the real vbmeta property is unavailable the attested key is at least
     * deterministic and tied to whatever keybox is actually signing the chain, rather than
     * an arbitrary persisted random value with no relation to anything else we attest.
     * Returns null if no keybox is currently loaded.
     */
    private static byte[] getBootKeyFromKeybox() {
        try {
            KeyBoxManager keyBoxManager = TrickyStoreService.getInstance().getKeyBoxManager();
            if (keyBoxManager == null || !keyBoxManager.hasKeyboxes()) {
                return null;
            }
            KeyBoxManager.KeyBox keybox = keyBoxManager.getKeybox("EC");
            if (keybox == null) {
                keybox = keyBoxManager.getKeybox("RSA");
            }
            if (keybox == null || keybox.certificates == null || keybox.certificates.isEmpty()) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(keybox.certificates.get(0).getEncoded());
        } catch (Exception e) {
            Log.w(TAG, "Failed to derive boot key from keybox issuer", e);
            return null;
        }
    }

    /**
     * Reads the real verified-boot public key digest from the bootloader property, so the
     * RootOfTrust key we attest matches the device's actual verified-boot chain instead of a
     * value with no relation to it. Falls back to the persisted random value only when the
     * property is unavailable.
     */
    private static byte[] getBootKeyFromProp() {
        String digest = SystemProperties.get("ro.boot.vbmeta.public_key_digest", null);
        if (digest == null || digest.isEmpty() || digest.length() != 64) {
            return null;
        }
        try {
            return hexStringToByteArray(digest);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse vbmeta.public_key_digest", e);
            return null;
        }
    }

    public static synchronized byte[] getBootHash() {
        Object version = sKeyboxVersion;
        if (sBootHash == null || (sBootHashFallback && sBootHashVersion != version)) {
            sBootHashVersion = version;
            sBootHash = getBootHashFromProp();
            if (sBootHash == null && !sTeeBroken) {
                sBootHash = extractBootHashFromTee();
            }
            sBootHashFallback = sBootHash == null;
            if (sBootHash == null) {
                sBootHash = deriveBootHashFromKeybox();
            }
            if (sBootHash == null) {
                // Persisted values have no measurement provenance and may predate
                // an OTA. Use them only after current hardware/keybox sources.
                sBootHash = loadOrCreatePersisted(HBK_FILE);
            }
        }
        return sBootHash;
    }

    public static synchronized void initBootHash() {
        Log.i(TAG, "initBootHash: Starting boot hash initialization");
        byte[] hash = getBootHash();
        if (getBootHashFromProp() == null) {
            Log.i(TAG, "initBootHash: hash extracted/generated, setting prop");
            setVbmetaDigestProp(bytesToHex(hash));
        } else {
            Log.i(TAG, "initBootHash: Existing vbmeta digest property found");
        }
    }

    private static void setVbmetaDigestProp(String digest) {
        final String prop = "ro.boot.vbmeta.digest";
        if (digest == null || !digest.matches("[0-9a-fA-F]{64}")) {
            Log.e(TAG, "Rejecting invalid verified-boot digest format");
            return;
        }
        try {
            SystemProperties.set(prop, digest.toLowerCase(java.util.Locale.ROOT));
            String observed = SystemProperties.get(prop, "");
            if (observed.equalsIgnoreCase(digest)) {
                Log.i(TAG, "Verified-boot digest property update confirmed");
            } else {
                Log.e(TAG, "Verified-boot digest write returned but property read-back "
                        + "did not match; length=" + observed.length());
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to publish TEE-provided digest to " + prop, e);
        }
    }

    private static byte[] extractBootHashFromTee() {
        final String alias = "trickystore_attestation_key";
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);

            byte[] challenge = generateRandomBytes(32);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .build();
            kpg.initialize(spec);
            kpg.generateKeyPair();

            KeyDescriptor descriptor = new KeyDescriptor();
            descriptor.alias = alias;
            descriptor.domain = Domain.APP;
            descriptor.nspace = KeyProperties.NAMESPACE_APPLICATION;
            descriptor.blob = null;
            KeyEntryResponse entry = KeyStore2.getInstance().getKeyEntry(descriptor);
            if (entry == null || entry.metadata == null
                    || entry.metadata.certificate == null) {
                Log.w(TAG, "TEE probe returned no Keystore2 certificate metadata");
                return null;
            }
            X509Certificate leaf = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(entry.metadata.certificate));
            byte[] extValue = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
            if (extValue == null) {
                Log.w(TAG, "TEE probe leaf lacks an attestation extension");
                return null;
            }

            try (ASN1InputStream extStream = new ASN1InputStream(extValue)) {
                ASN1OctetString octet = ASN1OctetString.getInstance(extStream.readObject());
                try (ASN1InputStream descriptionStream =
                        new ASN1InputStream(octet.getOctets())) {
                    ASN1Sequence description = ASN1Sequence.getInstance(
                            descriptionStream.readObject());
                    if (description.size() < 8) {
                        Log.w(TAG, "TEE probe produced an incomplete KeyDescription");
                        return null;
                    }
                    int attestationLevel = ASN1Enumerated.getInstance(
                            description.getObjectAt(1)).getValue().intValue();
                    int keymasterLevel = ASN1Enumerated.getInstance(
                            description.getObjectAt(3)).getValue().intValue();
                    if ((attestationLevel != 1 && attestationLevel != 2)
                            || (keymasterLevel != 1 && keymasterLevel != 2)) {
                        Log.w(TAG, "TEE probe was not hardware attested, attestationLevel="
                                + attestationLevel + ", keymasterLevel=" + keymasterLevel);
                        return null;
                    }
                    byte[] attestedChallenge = ASN1OctetString.getInstance(
                            description.getObjectAt(4)).getOctets();
                    if (!MessageDigest.isEqual(challenge, attestedChallenge)) {
                        Log.w(TAG, "TEE probe attestation challenge mismatch");
                        return null;
                    }
                    ASN1Sequence tee = ASN1Sequence.getInstance(description.getObjectAt(7));
                    for (int i = 0; i < tee.size(); i++) {
                        ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(tee.getObjectAt(i));
                        if (tagged.getTagNo() != 704) continue;
                        ASN1Sequence rootOfTrust = ASN1Sequence.getInstance(
                                tagged.getBaseObject());
                        if (rootOfTrust.size() < 4) {
                            Log.i(TAG, "TEE reports legacy RootOfTrust without verifiedBootHash");
                            return null;
                        }
                        byte[] hash = ASN1OctetString.getInstance(
                                rootOfTrust.getObjectAt(3)).getOctets();
                        if (hash.length != 32) {
                            Log.w(TAG, "TEE returned unexpected verifiedBootHash length: "
                                    + hash.length);
                            return null;
                        }
                        Log.i(TAG, "TEE probe returned a hardware-level verifiedBootHash");
                        return hash;
                    }
                }
            }
            Log.w(TAG, "TEE probe contained no RootOfTrust tag");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "TEE boot hash probe failed; RKP provisioning may be unavailable", e);
            return null;
        } finally {
            if (ks != null) {
                try {
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to remove temporary TEE probe key", e);
                }
            }
        }
    }

    public static byte[] getBootHashFromProp() {
        String digest = SystemProperties.get("ro.boot.vbmeta.digest", null);
        if (digest == null || digest.isEmpty() || digest.length() != 64) {
            return null;
        }
        try {
            return hexStringToByteArray(digest);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse vbmeta.digest", e);
            return null;
        }
    }

    public static int getOsVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q: return 100000;
            case Build.VERSION_CODES.R: return 110000;
            case Build.VERSION_CODES.S: return 120000;
            case Build.VERSION_CODES.S_V2: return 120100;
            case Build.VERSION_CODES.TIRAMISU: return 130000;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE: return 140000;
            case Build.VERSION_CODES.VANILLA_ICE_CREAM: return 150000;
            default: return (Build.VERSION.SDK_INT - 20) * 10000;
        }
    }

    public static int getAttestVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q:
            case Build.VERSION_CODES.R:
                return 4;
            case Build.VERSION_CODES.S:
            case Build.VERSION_CODES.S_V2:
                return 100;
            case Build.VERSION_CODES.TIRAMISU:
                return 200;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE:
            case Build.VERSION_CODES.VANILLA_ICE_CREAM:
                return 300;
            default:
                return 400;
        }
    }

    public static int getKeymasterVersion() {
        int attestVersion = getAttestVersion();
        return attestVersion == 4 ? 41 : attestVersion;
    }

    public static int getPatchLevel(boolean isLong) {
        return getPatchLevel(isLong, null);
    }

    public static int getPatchLevel(boolean isLong, String[] packages) {
        TrickyStoreService.CustomPatchLevel customLevel =
            TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages);
        if (customLevel != null && customLevel.system != null) {
            Integer parsed = parsePatchLevel(customLevel.system, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    public static int getVendorPatchLevel(boolean isLong) {
        return getVendorPatchLevel(isLong, null);
    }

    public static int getVendorPatchLevel(boolean isLong, String[] packages) {
        TrickyStoreService.CustomPatchLevel customLevel =
            TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages);
        if (customLevel != null && customLevel.vendor != null) {
            Integer parsed = parsePatchLevel(customLevel.vendor, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    public static int getBootPatchLevel(boolean isLong) {
        return getBootPatchLevel(isLong, null);
    }

    public static int getBootPatchLevel(boolean isLong, String[] packages) {
        TrickyStoreService.CustomPatchLevel customLevel =
            TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages);
        if (customLevel != null && customLevel.boot != null) {
            Integer parsed = parsePatchLevel(customLevel.boot, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    /** Check YYYYMM or YYYYMMDD patch dates (with optional dashes), including leap years. */
    public static boolean isValidPatchLevel(String value) {
        return parsePatchDate(value) != null;
    }

    private static final java.util.regex.Pattern PATCH_DATE_FORMAT =
            java.util.regex.Pattern.compile(
                    "(?:[0-9]{6}|[0-9]{8}|[0-9]{4}-[0-9]{2}|[0-9]{4}-[0-9]{2}-[0-9]{2})");

    /** Returns year, month and day. Month-only patch dates resolve to the first day. */
    private static int[] parsePatchDate(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (!PATCH_DATE_FORMAT.matcher(trimmed).matches()) return null;
        String normalized = trimmed.replace("-", "");
        if (normalized.length() != 6 && normalized.length() != 8) return null;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        try {
            int year = Integer.parseInt(normalized.substring(0, 4));
            int month = Integer.parseInt(normalized.substring(4, 6));
            int day = normalized.length() == 8
                    ? Integer.parseInt(normalized.substring(6, 8)) : 1;
            return isValidPatchDate(year, month, day)
                    ? new int[] {year, month, day} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isValidPatchDate(int year, int month, int day) {
        return year >= 1 && year <= 9999 && month >= 1 && month <= 12
                && day >= 1 && day <= java.time.YearMonth.of(year, month).lengthOfMonth();
    }

    private static Integer parsePatchLevel(String value, boolean isLong) {
        if (value == null || value.equalsIgnoreCase("no") || value.equalsIgnoreCase("prop")) {
            return null;
        }
        int[] date = parsePatchDate(value);
        if (date == null) {
            Log.w(TAG, "Ignoring invalid patch level: " + value);
            return null;
        }
        return isLong ? date[0] * 10000 + date[1] * 100 + date[2]
                : date[0] * 100 + date[1];
    }

    public static int convertPatchLevel(String patchString, boolean isLong) {
        int[] date = parsePatchDate(patchString);
        if (date != null) {
            return isLong ? date[0] * 10000 + date[1] * 100 + date[2]
                    : date[0] * 100 + date[1];
        }
        // Retain the previous fixed fallback instead of asserting today's date is a
        // device security patch. Invalid configured patch levels are ignored above.
        Log.e(TAG, "Invalid patch level format: " + patchString);
        return 202404;
    }

    public static byte[] computeModuleHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(new byte[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute module hash", e);
            return new byte[32];
        }
    }

    private static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * Loads a persisted value from {@code file}, generating and persisting
     * a fresh 32-byte random value on first run so it stays stable across
     * daemon restarts and reboots instead of being re-randomized every time.
     */
    private static byte[] loadOrCreatePersisted(File file) {
        byte[] existing = readPersisted(file);
        if (existing != null) {
            return existing;
        }
        byte[] fresh = generateRandomBytes(32);
        writePersisted(file, fresh);
        return fresh;
    }

    private static byte[] readPersisted(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (data.length == 32) {
                return data;
            }
            Log.w(TAG, "Persisted value at " + file + " has unexpected length "
                    + data.length + ", ignoring");
        } catch (IOException e) {
            Log.w(TAG, "Failed to read persisted value from " + file, e);
        }
        return null;
    }

    private static void writePersisted(File file, byte[] data) {
        try {
            if (!CONFIG_DIR.isDirectory() && !CONFIG_DIR.mkdirs()) {
                Log.w(TAG, "Failed to create " + CONFIG_DIR);
                return;
            }
            File tmp = new File(CONFIG_DIR, file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "Failed to rename " + tmp + " to " + file);
            } else {
                file.setReadable(true, true);
                file.setWritable(true, true);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to persist value to " + file, e);
        }
    }

    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) throw new IllegalArgumentException("Odd hexadecimal length");
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hexadecimal character");
            }
            data[i / 2] = (byte) ((hi << 4) | lo);
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

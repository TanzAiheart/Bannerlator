package com.winlator.star;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class Ch3xpPack {
    static final String ID = "vanilla-bee4d1f24fc6";
    private static final String PACK_FILE = "CH3XP-" + ID + ".zip";
    private static final long MAX_BYTES = 6L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 30000;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final String[] REQUIRED_FILES = {
        "Shkxp.exe", "Sh3DiskIdx.dat", "Sh3DiskImg.dat", "Shkop.dat", "Gameover.dat"
    };

    private static final class FileRecord {
        final long size;
        final String sha256;

        FileRecord(long size, byte[] digest) {
            this.size = size;
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                int unsigned = value & 255;
                hex.append(Character.forDigit(unsigned >>> 4, 16))
                   .append(Character.forDigit(unsigned & 15, 16));
            }
            sha256 = hex.toString();
        }
    }

    private Ch3xpPack() {}

    static void unpack(InputStream input, File staging) throws IOException, JSONException {
        unpack(input, staging, true);
    }

    private static void unpack(InputStream input, File staging, boolean allowWrapper)
            throws IOException, JSONException {
        if (!staging.isDirectory()) throw new IOException("Import staging folder is missing.");
        Map<String, FileRecord> files = new HashMap<>();
        Set<String> seen = new HashSet<>();
        JSONObject manifest = null;
        byte[] buffer = new byte[65536];
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
                validateName(name);
                if (seen.size() == MAX_ENTRIES || !seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Too many entries or duplicate ZIP path: " + name);
                }
                File destination = new File(staging, name);
                if (entry.isDirectory()) {
                    mkdir(destination);
                    if (zip.read() != -1) throw new IOException("Unexpected data in directory entry: " + name);
                } else if (name.equals("manifest.json")) {
                    ByteArrayOutputStream meta = new ByteArrayOutputStream();
                    int bytes;
                    while ((bytes = zip.read(buffer)) != -1) {
                        if ((long) meta.size() + bytes > MAX_MANIFEST_BYTES) throw new IOException("Manifest is too large.");
                        meta.write(buffer, 0, bytes);
                    }
                    manifest = new JSONObject(new String(meta.toByteArray(), StandardCharsets.UTF_8));
                    validateId(manifest);
                } else {
                    mkdir(destination.getParentFile());
                    MessageDigest digest = sha256();
                    long size = 0;
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        int bytes;
                        while ((bytes = zip.read(buffer)) != -1) {
                            total += bytes;
                            if (total > MAX_BYTES) throw new IOException("The ZIP exceeds the import size limit.");
                            size += bytes;
                            digest.update(buffer, 0, bytes);
                            output.write(buffer, 0, bytes);
                        }
                    }
                    files.put(name, new FileRecord(size, digest.digest()));
                }
                zip.closeEntry();
            }
        }
        if (manifest == null) {
            if (!allowWrapper || !files.containsKey(PACK_FILE)) {
                throw new IOException("No manifest.json found. Select " + PACK_FILE
                    + " or its original GitHub download ZIP.");
            }
            unpackWrapper(staging, files);
            return;
        }
        JSONObject expected = manifest.getJSONObject("files");
        Iterator<String> names = expected.keys();
        while (names.hasNext()) {
            String name = names.next();
            FileRecord actual = files.get(name);
            JSONObject listed = expected.getJSONObject(name);
            if (actual == null) throw new IOException("Missing from the ZIP: " + name);
            if (actual.size != listed.getLong("size")) throw new IOException("Wrong file size: " + name);
            if (!actual.sha256.equalsIgnoreCase(listed.getString("sha256"))) {
                throw new IOException("Checksum mismatch: " + name);
            }
        }
        for (String name : files.keySet()) {
            if (expected.has(name)) continue;
            if (!name.equals(PACK_FILE) && !isSidecar(name)) {
                throw new IOException("File is not listed in the pack manifest: " + name);
            }
            if (!new File(staging, name).delete()) throw new IOException("Could not remove extra file: " + name);
        }
        for (String name : REQUIRED_FILES) {
            if (!expected.has(name)) throw new IOException("Missing required game file: " + name);
        }
        boolean intro = false;
        Iterator<String> expectedNames = expected.keys();
        while (expectedNames.hasNext()) {
            if ("data/shkintro.sh3".equalsIgnoreCase(expectedNames.next())) intro = true;
        }
        if (!intro) throw new IOException("Missing the Disc 2 New Game intro in data/.");
    }

    private static void unpackWrapper(File staging, Map<String, FileRecord> files)
            throws IOException, JSONException {
        for (String name : files.keySet()) {
            if (!name.equals(PACK_FILE) && !isSidecar(name)) {
                throw new IOException("Unexpected file in the download ZIP: " + name);
            }
        }
        File unpacked = new File(staging, ".ch3xp-inner");
        if (!unpacked.mkdir()) throw new IOException("Could not open the inner ZIP staging folder.");
        try (InputStream input = new FileInputStream(new File(staging, PACK_FILE))) {
            unpack(input, unpacked, false);
        }
        for (String name : files.keySet()) {
            if (!new File(staging, name).delete()) throw new IOException("Could not remove wrapper file: " + name);
        }
        File[] entries = unpacked.listFiles();
        if (entries == null) throw new IOException("Could not list the inner ZIP files.");
        for (File entry : entries) {
            if (!entry.renameTo(new File(staging, entry.getName()))) {
                throw new IOException("Could not move the imported folder: " + entry.getName());
            }
        }
        if (!unpacked.delete()) throw new IOException("Could not close the inner ZIP staging folder.");
    }

    private static boolean isSidecar(String name) {
        return name.equals(PACK_FILE + ".sha256")
                || name.startsWith("__MACOSX/")
                || name.endsWith("/.DS_Store") || name.equals(".DS_Store")
                || name.endsWith("/Thumbs.db") || name.equals("Thumbs.db");
    }

    private static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.indexOf(0) >= 0) throw new IOException("Invalid ZIP path: " + name);
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")
                    || part.endsWith(".") || part.endsWith(" ")) throw new IOException("Invalid ZIP path: " + name);
        }
    }

    private static void validateId(JSONObject manifest) throws IOException {
        if (!ID.equals(manifest.optString("id"))) {
            throw new IOException("Select CH3XP-" + ID + ".zip.");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory.getName());
        }
    }
}

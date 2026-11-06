package local.tscosts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProfileDataTest {
    public static void main(String[] args) throws Exception {
        ProfileData profile = ProfileData.read(Path.of(args[0]));
        if (profile.files.isEmpty()) throw new AssertionError("No measured source files");
        long hints = profile.files.stream().mapToLong(file -> file.hints.size()).sum();
        if (profile.schemaVersion == 2 && (profile.summary == null || profile.summary.cpuMs == null
            || profile.summary.cpuMs <= 0 || profile.summary.peakRssBytes == null || profile.summary.peakRssBytes <= 0)) {
            throw new AssertionError("Missing measured compiler CPU or memory");
        }
        if (!ProfileData.fingerprint("\uFEFFtype A = string\r\n").equals(ProfileData.fingerprint("type A = string\n"))) {
            throw new AssertionError("IDE-normalized source must retain its measurement identity");
        }
        if (ProfileData.fingerprint("type A = number\n").equals(ProfileData.fingerprint("type A = string\n"))) {
            throw new AssertionError("Changed source must hide stale costs");
        }
        Path invalid = Files.createTempFile("ts-costs-invalid-", ".json");
        try {
            String hash = "a".repeat(64);
            Files.writeString(invalid, "{\"schemaVersion\":1,\"projectRoot\":\"/tmp\",\"files\":[{\"path\":\"../outside.ts\",\"sha256\":\""
                + hash + "\",\"fileMs\":1,\"hints\":[]}]}");
            try {
                ProfileData.read(invalid);
                throw new AssertionError("Profiles must not resolve source files outside the project");
            } catch (IOException expected) {}
            Files.writeString(invalid, "{\"schemaVersion\":999,\"projectRoot\":\"/tmp\",\"files\":[]}");
            try {
                ProfileData.read(invalid);
                throw new AssertionError("Unsupported schemas must be rejected");
            } catch (IOException expected) {}
        } finally { Files.deleteIfExists(invalid); }
        System.out.println("Profile model checks passed: " + profile.files.size() + " files, " + hints + " hints");
    }
}

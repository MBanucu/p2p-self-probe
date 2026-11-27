package org.phonelink.p2pprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Plain-JDK tests for {@link ProbeLogic} plus a check of the recorded run-3
 * fixture. No JUnit: the flake only needs a JDK, and `java ProbeLogicTest`
 * exits non-zero on the first broken assumption.
 */
public final class ProbeLogicTest {
    private static int checks;

    private ProbeLogicTest() {}

    public static void main(String[] args) throws IOException {
        maskedPlaceholders();
        selfMatch();
        staKept();
        verdict();
        if (args.length > 0) {
            fixture(Path.of(args[0]));
        }
        System.out.println("ok: " + checks + " checks");
    }

    private static void maskedPlaceholders() {
        check(ProbeLogic.isMasked(null), "null is masked");
        check(ProbeLogic.isMasked(""), "empty is masked");
        check(ProbeLogic.isMasked("02:00:00:00:00:00"), "02:.. placeholder is masked");
        check(ProbeLogic.isMasked("00:00:00:00:00:00"), "00:.. placeholder is masked");
        check(ProbeLogic.isMasked("02:00:00:00:00:00".toUpperCase()), "mask check is case-insensitive");
        check(!ProbeLogic.isMasked("d2:05:65:2e:5e:38"), "a real P2P MAC is not masked");
    }

    private static void selfMatch() {
        String own = "02:00:00:00:00:00";
        String real = "d2:05:65:2e:5e:38";
        check(ProbeLogic.matchesSelf(own, real, real), "the override identifies us");
        check(ProbeLogic.matchesSelf(real, "", real), "the framework address identifies us when unmasked");
        check(!ProbeLogic.matchesSelf(own, "", "d2:05:65:2e:5e:38"), "a masked own address matches nothing");
        check(!ProbeLogic.matchesSelf(own, "02:00:00:00:00:00", "02:00:00:00:00:00"),
                "a masked override is ignored");
        check(!ProbeLogic.matchesSelf(own, real, "06:08:22:1c:47:fd"), "a different peer is not us");
        check(!ProbeLogic.matchesSelf(own, real, null), "null peer is not us");
    }

    private static void staKept() {
        check(ProbeLogic.staKept(true, true, true), "up before/during/after is kept");
        check(!ProbeLogic.staKept(true, false, true), "a drop during the group is not kept");
        check(!ProbeLogic.staKept(false, true, true), "no link before is not kept");
        check(!ProbeLogic.staKept(true, true, false), "a drop after the group is not kept");
    }

    private static void verdict() {
        String impossible = ProbeLogic.verdict(false, "failed ERROR", "failed ERROR");
        check(impossible.startsWith("self P2P is not possible"),
                "failed self-connect and own-group connect rule self P2P out");
        check(ProbeLogic.verdict(true, "failed ERROR", "failed ERROR").startsWith("self P2P may be possible"),
                "a self peer is a positive signal");
        check(ProbeLogic.verdict(false, "accepted", "failed ERROR").startsWith("self P2P may be possible"),
                "an accepted self-connect is a positive signal");
        check(ProbeLogic.verdict(false, "failed ERROR", "accepted").startsWith("self P2P may be possible"),
                "an accepted own-group connect is a positive signal");
    }

    /** Run 3 is the real-MAC run with the group held for 8 s. */
    private static void fixture(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String text = String.join("\n", lines);

        check(text.contains("address override from intent: d2:05:65:2e:5e:38"),
                "run 3 supplied the real p2p0 MAC");
        check(text.contains("this device: XPLORE 2 02:00:00:00:00:00 (masked by the framework)"),
                "the framework still masks the local address");
        check(text.contains("peers: 0 total, self listed: false"),
                "discovery returned no peers and no self");
        check(text.contains("connect(self): failed ERROR"),
                "connecting to the real local MAC failed");
        check(text.contains("createGroup: success"), "the group was created");
        check(text.contains("group owner: true"), "the phone became group owner");
        check(text.contains("group clients: 0"), "no client joined the phone's own group");
        check(text.contains("connect(own group): failed ERROR"),
                "connecting to the phone's own group owner failed");
        check(text.contains("sta client link kept through the group: true"),
                "the STA link stayed up before/during/after");
        check(text.contains("VERDICT self P2P is not possible"),
                "the recorded verdict rules self P2P out");
        check(text.contains(ProbeLogic.verdict(false, "failed ERROR", "failed ERROR")),
                "the fixture verdict matches ProbeLogic.verdict");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}

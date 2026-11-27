package org.phonelink.p2pprobe;

/**
 * The probe's decisions, with no Android types, so a plain JDK test covers
 * them: what counts as a masked address, whether a peer is us, whether the STA
 * link survived the group, and the final verdict.
 */
public final class ProbeLogic {
    private ProbeLogic() {}

    /** The framework reports these placeholders instead of the local P2P MAC. */
    public static boolean isMasked(String address) {
        return address == null
                || address.isEmpty()
                || "02:00:00:00:00:00".equalsIgnoreCase(address)
                || "00:00:00:00:00:00".equalsIgnoreCase(address);
    }

    /**
     * A peer is "us" when it matches either the address the framework reported
     * or the one supplied by the host (the framework masks the local one on
     * this ROM, so the real p2p0 MAC has to come from outside).
     */
    public static boolean matchesSelf(String own, String override, String peer) {
        if (peer == null || peer.isEmpty()) {
            return false;
        }
        // A masked placeholder must never make a peer look like us; on this ROM
        // the framework reports 02:00:00:00:00:00 for the local device.
        if (override != null && !isMasked(override) && override.equalsIgnoreCase(peer)) {
            return true;
        }
        return own != null && !isMasked(own) && own.equalsIgnoreCase(peer);
    }

    /** The STA link is kept only if it was up before, during and after. */
    public static boolean staKept(boolean before, boolean during, boolean after) {
        return before && during && after;
    }

    public static String verdict(boolean selfInPeers, String selfConnect, String groupConnect) {
        if (selfInPeers || "accepted".equals(selfConnect) || "accepted".equals(groupConnect)) {
            return "self P2P may be possible; inspect the log";
        }
        return "self P2P is not possible: the phone cannot discover or connect to itself";
    }
}

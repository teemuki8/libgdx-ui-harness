package dev.gdx.uiharness.core.layout;

import java.util.Objects;

/** One completed rendered-frame sample used by the scroll quiescence gate. */
public record LayoutStabilitySample(
        long frame,
        long semanticRevision,
        long layoutRevision,
        double scrollX,
        double scrollY,
        double maxScrollX,
        double maxScrollY,
        String viewportBoundsSha256,
        String contentBoundsSha256,
        String clipChainSha256,
        String layoutSha256,
        String framebufferSha256,
        boolean activeScroll) {
    /** Validates ordered identity and finite scroll values. */
    public LayoutStabilitySample {
        if (frame < 0 || semanticRevision < 0 || layoutRevision < 0) {
            throw new IllegalArgumentException("frame and revisions must be non-negative");
        }
        for (double value : new double[] {scrollX, scrollY, maxScrollX, maxScrollY}) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("scroll values must be finite");
            }
        }
        LayoutSupport.nonBlank(viewportBoundsSha256, "viewportBoundsSha256");
        LayoutSupport.nonBlank(contentBoundsSha256, "contentBoundsSha256");
        LayoutSupport.nonBlank(clipChainSha256, "clipChainSha256");
        LayoutSupport.nonBlank(layoutSha256, "layoutSha256");
        LayoutSupport.nonBlank(framebufferSha256, "framebufferSha256");
        Objects.requireNonNull(viewportBoundsSha256);
    }

    /** Returns true when every stability signal agrees and frames are consecutive. */
    public boolean stableAfter(LayoutStabilitySample previous) {
        return frame == previous.frame + 1
                && layoutRevision == previous.layoutRevision
                && Double.compare(scrollX, previous.scrollX) == 0
                && Double.compare(scrollY, previous.scrollY) == 0
                && Double.compare(maxScrollX, previous.maxScrollX) == 0
                && Double.compare(maxScrollY, previous.maxScrollY) == 0
                && viewportBoundsSha256.equals(previous.viewportBoundsSha256)
                && contentBoundsSha256.equals(previous.contentBoundsSha256)
                && clipChainSha256.equals(previous.clipChainSha256)
                && layoutSha256.equals(previous.layoutSha256)
                && framebufferSha256.equals(previous.framebufferSha256)
                && !activeScroll
                && !previous.activeScroll;
    }
}

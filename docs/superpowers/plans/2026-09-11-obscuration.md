# Scene2D obscuration correctness

The 2026-09-11 shooter capture exposed full-viewport backgroundless layout Tables reported
as occluders, and sibling-local z indices compared across unrelated branches. The authorized
fix preserves actual visual and interactive overlap diagnostics.

1. Add `LayoutValidatorTest` branch-order regression and `Scene2dLayoutValidatorTest`
   backgroundless sibling Table regression; run each focused test and retain failing output.
2. In `LayoutValidator.checkObscured`, use existing depth-first child order, exclude ancestor
   composition, and skip adapter-proven nonpainting containers only when noninteractive.
3. In `Scene2dSnapshotter`, reserve `scene2d.layoutOnly` for exact known builtin layout
   containers. Background-bearing Tables and custom subclasses remain conservative candidates.
   Capture remains render-thread confined; the one additional bounded property uses existing limits.
4. Preserve positive tests for background and interactive overlays. Document the reserved
   metadata and conservative limits in ADR 0041. Run core and Scene2D tests plus checks/Javadoc.
5. Parent rebuilds and recaptures the shooter; no previous receipts are rewritten. Full harness
   gate remains `xvfb-run -a ./gradlew clean check javadoc --warning-mode=fail --no-daemon
   --console=plain`, executed through the bootstrap isolation script with X11/software rendering.

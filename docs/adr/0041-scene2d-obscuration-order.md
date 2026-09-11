# ADR 0041: Scene2D obscuration follows draw order and nonpainting container evidence

- Date: 2026-09-11
- Status: Accepted for the local candidate

## Context

`SemanticNode.zIndex` is sibling-local. Comparing it across branches falsely orders children
of a back layer above a later front layer. Backgroundless full-stage Tables also have layout
bounds without painting those bounds, so treating every visible actor rectangle as a visual
occluder reports HUD/menu composition as obscured.

## Decision

`OBSCURED` uses the snapshot's depth-first child order, matching standard Scene2D Group draw
order, and retains ancestor/descendant composition exclusion. Sibling-local z indices remain
unchanged in the public snapshot. This corrects both missed front-layer occlusion and false
reverse-layer reports.

The Scene2D snapshotter reserves the bounded property `scene2d.layoutOnly=true` for exact
`Group`, `WidgetGroup`, and backgroundless `Table` instances. It derives this evidence after
semantic contributions on the render thread. Application metadata cannot override it. Custom
subclasses are not inferred because they may override drawing. Background-bearing Tables also
remain candidates; the adapter does not claim to infer arbitrary Drawable opacity. Custom Group
draw overrides that reorder children are outside this standard drawing-order model.

The validator excludes these proven nonpainting containers as obscuration targets and
occluders only when neither touchable nor focusable. Their descendants are still examined.
Interactive overlap validation is unchanged, and input-blocking containers remain candidates.
Other snapshot producers without this property retain conservative rectangle overlap behavior.
No node, drawable, or mutable backend reference crosses the semantic boundary. Existing
property-count and snapshot-byte bounds apply to this extra property. A builtin layout container
already carrying 256 custom properties now fails explicitly at that bound rather than silently
dropping either application metadata or adapter evidence.

## Qualification

Core regression reproduces unrelated sibling indices and asserts the front actor is never
reported under its back-layer counterpart. Existing ancestor-composition regression now
correctly reports both a covered button and its covered label. Scene2D regressions reproduce
two full-stage transparent Tables with separate content, then preserve positive diagnostics
for background-bearing and interactive Tables. Custom drawing remains conservative.

Run focused core and Scene2D tests, followed by the repository full `clean check javadoc` gate
under isolated software X11. Consumers must rebuild and recapture evidence after this semantic
correction; existing findings and receipts are not rewritten.

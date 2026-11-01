# Observation effects

Choose `Observation effect` in the Android app:

- `Frame glow` is the default: one rounded-frame illumination over 700ms.
- `Scan line` retains the 1400ms moving observation cue.
- `Off` starts no observation animation. Connection and control feedback remain.

The selection persists and updates a connected service without changing its
control ownership. Switching modes clears the previous cue. Observation bursts
coalesce rather than repeatedly restarting an animation. Stop, completion, and
disconnect clear the current cue; captures exclude Henyo's own overlays by
default. No operation waits for either observation animation to finish.

The normal activity frame breathes slowly and faintly. The observation frame
uses a brief, stronger teal pulse so it remains visible on both light and dark
backgrounds. Frame glow and Off leave cursor feedback visible; Scan line retains
its prior cursor-suppression behavior while scanning.

Frames follow the selected target's visible client extent on its display. The
outline is inset and continuous even when the presentation region has holes.
When less than 65% of that extent remains visible, four intentional corner
brackets replace the full outline. Fully hidden targets have no frame. Frame
geometry is rebuilt only when target geometry changes; it uses cached paths
instead of screen-sized pixel buffers. Actionable regions, protected-window
checks, and capture identity remain independent and unchanged.

Pure model tests cover timing, coalescing, mode changes, cursor behavior, and
frame geometry across offsets, densities, resizing, and occlusion. Device
verification should compare light/dark appearance, clean captures, latency,
setting persistence, overlap, split-screen, and available display transitions.
Keep captures and raw measurements under ignored `artifacts/`.

#!/usr/bin/env python3
from pathlib import Path
import struct


ROOT = Path(__file__).resolve().parents[1]
OVERLAY = (ROOT / "src/main/java/link/liaru/henyo/ConnectionStatusOverlay.java").read_text()
MODEL = (ROOT / "src/main/java/link/liaru/henyo/AgentActivityModel.java").read_text()
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
VISUAL = (ROOT / "src/main/java/link/liaru/henyo/AgentVisualModel.java").read_text()
GLOVE_ASSETS = [
    ROOT / "src/main/res/drawable-nodpi/agent_glove_point.png",
    ROOT / "src/main/res/drawable-nodpi/agent_glove_swipe.png",
    ROOT / "src/main/res/drawable-nodpi/agent_glove_back.png",
]


def require(source: str, marker: str, message: str) -> None:
    if marker not in source:
        raise AssertionError(message)


def forbid(source: str, marker: str, message: str) -> None:
    if marker in source:
        raise AssertionError(message)


require(OVERLAY, "TYPE_ACCESSIBILITY_OVERLAY", "indicator must use the accessibility overlay window type")
require(OVERLAY, "FLAG_NOT_TOUCHABLE", "indicator must pass touch input through")
require(OVERLAY, "FLAG_NOT_FOCUSABLE", "indicator must not take input focus")
require(OVERLAY, "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS", "indicator must stay out of accessibility traversal")
require(OVERLAY, "ACTIVE_HOLD_MS = 20_000L", "indicator must bridge Sol-sized gaps between agent actions")
require(VISUAL, "ACTIVE_LEASE_MS = 20_000L", "visual state must share the twenty-second activity lease")
require(VISUAL, "ACTIVATION_MS = 800L", "first activation and fade reversal must ease in")
require(VISUAL, "BACKDROP_ACTIVATION_MS = 120L", "navy contrast must establish before caption reveal")
require(OVERLAY, "GLOW_FADE_OUT_MS = 1_800L", "active glow must fade out gradually")
require(OVERLAY, "finishFadeOutOnMain", "edge views must remain until fade completion")
require(OVERLAY, "mainHandler.removeCallbacks(finishFadeOut)", "new activity must reverse an in-flight fade")
require(OVERLAY, "removeViewImmediate", "indicator must be removed on disconnect")
require(OVERLAY, "LinearGradient", "indicator must draw a gradient")
require(OVERLAY, "RadialGradient", "active indicator must draw a firefly glow")
require(OVERLAY, "drawInnerGlow", "active indicator must draw around the display perimeter")
require(OVERLAY, "WindowManager.LayoutParams.MATCH_PARENT", "active indicator must cover the display")
require(OVERLAY, "beginScreenshotSuppression", "indicator must support clean Henyo captures")
require(OVERLAY, "removeView();", "older Android versions need reliable overlay detachment")
require(OVERLAY, "48L", "fallback suppression must wait for compositor frames")
require(SERVICE, "takeScreenshotOfWindow", "modern Android must capture beneath accessibility overlays")
require(SERVICE, "Build.VERSION.SDK_INT >= 34", "window capture must be API guarded")
require(SERVICE, "!windowScopedCapture", "overlay suppression must be skipped for window-scoped capture")
require(OVERLAY, "ICON_BACKGROUND", "indicator must use the icon background palette")
require(OVERLAY, "ICON_FOREGROUND", "indicator must use the icon foreground palette")
require(OVERLAY, "postInvalidateOnAnimation", "active gradient must animate")
require(OVERLAY, "idleAmount", "idle connection state must use the inward edge glow")
require(OVERLAY, "8_000L", "idle connection glow must breathe slowly")
require(OVERLAY, "TargetGlowModel.breath(now)", "active glow must use an event-independent global phase")
forbid(OVERLAY, "ValueAnimator", "view-local animators would reset phase across detach and restore")
require(OVERLAY, "onSizeChanged", "indicator shaders must be rebuilt only for geometry changes")
require(OVERLAY, "rebuildShaders", "indicator shaders must be cached outside frame drawing")
indicator_draw = OVERLAY.split("private void drawInnerGlow", 1)[1].split("private void drawCornerGlow", 1)[0]
forbid(indicator_draw, "new LinearGradient", "inner glow must not allocate a linear shader per frame")
forbid(indicator_draw, "new RadialGradient", "inner glow must not allocate a radial shader per frame")
forbid(OVERLAY, "setLocalMatrix", "indicator gradient must not travel horizontally")
forbid(OVERLAY, "244, 80, 173", "indicator must not retain the old pink rainbow color")

require(OVERLAY, "void noteControlActivity(String summary)", "control activity must accept a natural-language summary")
require(OVERLAY, "visualModel.noteActivity", "caption-free operations must begin the shared visual lease")
require(OVERLAY, "Henyo agent activity", "intent captions need their own overlay window")
require(OVERLAY, "TEXT_FILL", "captions must have a high-contrast off-white fill")
require(OVERLAY, "TEXT_GLOW", "captions must use a restrained cyan glow")
require(OVERLAY, "20f * scaledDensity", "captions must use the larger readable text size")
require(OVERLAY, "Typeface.BOLD", "captions must use bold text instead of a dark outline")
require(OVERLAY, "setShadowLayer", "caption cyan glow must be softly blurred")
require(OVERLAY, "setMaxLines(2)", "captions must stay compact")
require(OVERLAY, "RowLayoutCache", "caption layouts must be cached per message")
require(OVERLAY, "IdentityHashMap", "caption caches must follow message identity")
require(OVERLAY, "renderPaint.setAlpha", "caption dimming must use paint alpha")
require(OVERLAY, "PANEL_FILL = Color.rgb(27, 30, 35)", "comments and plan need a neutral surface")
require(OVERLAY, "ActivityPanelLayout panelLayout", "one measured layout must own the shared surface")
require(OVERLAY, "panelBorderPaint", "the panel must have a restrained boundary")
forbid(OVERLAY, "progressActivityShadeShader", "independent gradient lobes must not return")
forbid(OVERLAY, "activityGridPath", "the panel must not retain the heavy background grid")
require(OVERLAY, "height - 56f * density", "caption stack must sit closer to the bottom edge")
require(OVERLAY, "spinnerIndentPx", "spinner must occupy a dedicated indicator column")
require(OVERLAY, "displayBodyText", "caption body must be laid out separately from the spinner")
require(OVERLAY, "drawSpinner", "newest row must draw its spinner independently")
require(OVERLAY, "drawActivityBackground", "caption background must be drawn beneath text")
require(OVERLAY, "visualModel.backdropEnvelope(now)", "navy background must use its faster shared-lease envelope")
require(OVERLAY, "WindowManager.LayoutParams.MATCH_PARENT,\n                WindowManager.LayoutParams.MATCH_PARENT",
        "scan, glove, backdrop, and captions must share one full-screen canvas")
activity_draw = OVERLAY.split("protected void onDraw(Canvas canvas)", 2)[2].split(
    "private void drawTargetWindowGlow", 1
)[0]
scan_index = activity_draw.index("drawObservationEffect")
glove_index = activity_draw.index("drawGlove")
background_index = activity_draw.index("drawActivityBackground")
caption_index = activity_draw.index("drawCaption")
if not (scan_index < glove_index < background_index < caption_index):
    raise AssertionError("composite draw order must keep scan and glove below navy and captions")
require(OVERLAY, "safeTop", "scan must attenuate before the caption safe area")
require(OVERLAY, "(safeTop - y)", "scan attenuation must reach zero before entering caption rows")
note_activity = OVERLAY.split("private void noteControlActivityOnMain", 1)[1].split(
    "private boolean setTaskProgressOnMain", 1
)[0]
if note_activity.rfind("ensureActivityView();") < note_activity.index("if (!active)"):
    raise AssertionError("first caption-free operation must attach the composite view after active becomes true")
require(OVERLAY, "showObservation", "logical observations must start a scan")
require(OVERLAY, "GLOVE_POSE_POINT", "target actions must use the pointing glove")
require(OVERLAY, "GLOVE_POSE_OPEN_PALM", "swipes must use the open-palm glove")
require(OVERLAY, "GLOVE_POSE_BACK_LEFT", "Back must use the left-pointing glove")
require(OVERLAY, "float size = 58f * density", "persistent glove must use the smaller 58dp size")
forbid(OVERLAY, "float size = 86f * density", "legacy oversized 86dp glove must be removed")
require(OVERLAY, "float glowOutset = 2.25f * density", "smaller glove must use the restrained halo")
require(OVERLAY, "WRIST_X", "all poses must use explicit common wrist anchors")
require(OVERLAY, "HOTSPOT_X", "all poses must map their action hotspot to real coordinates")
forbid(OVERLAY, "poseBlend", "pose transitions must never draw two glove sprites")
forbid(OVERLAY, "previousPose", "only one glove pose may be retained for rendering")
require(OVERLAY, "drawGloveBitmap(canvas, frame.pose, gloveWrist[0], gloveWrist[1], alpha)",
        "each frame must draw exactly the model-selected glove sprite")
require(OVERLAY, "prepareAction", "cursor geometry must retain the prior action endpoint")
require(OVERLAY, "commitPreparedAction", "real actions must explicitly commit after cursor arrival")
require(OVERLAY, "AtomicBoolean pending", "timed-out cursor preparation and commit callbacks must be cancelled")
require(OVERLAY, "moveCursorPixels", "cursor travel time must scale with display distance")
require(OVERLAY, "BitmapFactory.decodeResource", "generated glove sprites must be decoded once per view")
require(OVERLAY, "PorterDuffColorFilter", "cyan glove glow must be rendered separately by the app")
require(OVERLAY, "agent_glove_swipe", "open-hand dorsal sprite must be bundled as an Android resource")
for asset in GLOVE_ASSETS:
    data = asset.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise AssertionError(f"glove asset must be PNG: {asset.name}")
    width, height = struct.unpack(">II", data[16:24])
    if (width, height) != (256, 256) or data[25] != 6:
        raise AssertionError(f"glove asset must be 256px RGBA: {asset.name}")
require(OVERLAY, "CaretSlotSpan", "newest caption geometry must reserve a fixed-width caret slot")
require(MODEL, "SPINNER_FRAMES", "newest caption must animate the requested Braille spinner")
require(OVERLAY, "drawCaret", "newest caption must draw a terminal scan-line caret")
require(OVERLAY, "caretHeightPx = 3f * density", "terminal caret must remain a thin bottom bar")
require(MODEL, "caretAlpha", "terminal caret must animate deterministically")
require(MODEL, "CARET_CYCLE_MS = 1_000L", "terminal caret must use a vintage one-second cycle")
require(MODEL, "smoothstep", "terminal caret fades must use eased interpolation")
forbid(OVERLAY, "saveLayerAlpha", "caption animation must not allocate an offscreen layer per row and frame")
forbid(OVERLAY, "renderPaint.setStyle(Paint.Style.STROKE)", "bold captions must not retain a dark outline pass")
forbid(OVERLAY, "activityHaloPaint", "lower navy shadow must not have a separate card halo")
require(OVERLAY, "drawRoundRect(activityPanelRect", "one rounded surface must sit behind the content")
forbid(activity_draw, "StaticLayout.Builder", "caption layout must not be built in the per-frame draw loop")
forbid(activity_draw, "new RadialGradient", "caption glow must not allocate shaders per frame")
forbid(activity_draw, "new Path", "caption grid must not allocate paths per frame")
forbid(activity_draw, "BitmapFactory", "glove bitmaps must not decode in the frame loop")
require(OVERLAY, "removeActivityView();", "screenshot suppression must detach captions too")
forbid(OVERLAY, "[入力]", "captions must not include category labels")
forbid(OVERLAY, "[観察]", "captions must not include category labels")

require(MODEL, "MAX_MESSAGES = 3", "caption stack must be bounded")
require(MODEL, "MAX_SUMMARY_CODE_POINTS", "caption length must be bounded by Unicode code points")
require(MODEL, "newest.summary.equals(summary)", "duplicate captions must coalesce")
require(MODEL, "appendCodePoint", "caption sanitization must preserve Unicode boundaries")
require(MODEL, "Character.isISOControl", "caption control characters must be sanitized")
require(MODEL, "visibleText", "captions must reveal progressively")
require(MODEL, "cachedSnapshot", "caption animation must not copy the message list every frame")

PROGRESS = (ROOT / "src/main/java/link/liaru/henyo/TaskProgressModel.java").read_text()
require(PROGRESS, "MAX_COMPLETED = 3", "completed progress history must be bounded")
require(PROGRESS, "MAX_STEPS = 6", "structured full plan must be visibly bounded")
require(PROGRESS, "UPDATE_PLAN_MISMATCH", "normal progress updates must preserve plan identity")
require(PROGRESS, "STATUS_PENDING", "pending plan steps must remain visible")
require(PROGRESS, "STATUS_IN_PROGRESS", "the active plan step must remain visible")
require(PROGRESS, "MAX_TEXT_CODE_POINTS = 72", "all progress text must be bounded")
require(PROGRESS, "Character.isISOControl", "progress control characters must be sanitized")
require(PROGRESS, "visibleText", "progress rows must keep typewriter reveal")
require(OVERLAY, "drawTaskProgress", "overlay must render task-level progress")
require(OVERLAY, "drawProgressIcon", "progress roles must use drawn monochrome icons")
require(OVERLAY, "progressCheckPath", "completed state must use vector check geometry")
require(OVERLAY, "progressArcRect", "current state must use allocation-free vector arc geometry")
require(OVERLAY, "float planTop = panelLayout.planTop;",
        "plan and captions must use measured non-overlapping geometry")
require(OVERLAY, "drawActivityBackground(canvas, backgroundAlpha, panelWidth, now)",
        "one shared background must cover measured content")
require(OVERLAY, "panelLayout.showPlanSummary",
        "small viewports must make omitted plan rows explicit")
if activity_draw.count("drawActivityBackground(") != 1:
    raise AssertionError("comments and plan must use one shared background pass")
forbid(activity_draw, "new LinearGradient", "panel rendering must not allocate shaders per frame")
require(OVERLAY, "TaskProgressModel.KIND_PENDING",
        "pending steps must use a distinct subdued icon")
require(OVERLAY, "activityModel.removeSummary(progressModel.current())",
        "progress updates must remove an identical operation caption")
require(OVERLAY, "equals(progressModel.current())",
        "operation captions identical to current progress must be suppressed")
for marker in ("🎯", "✅"):
    forbid(OVERLAY, marker, "progress icons must not depend on colorful emoji")

require(SERVICE, "refreshConnectionStatusOverlay();", "session changes must refresh connection visibility")
require(SERVICE, "noteControlActivity(", "control frames must promote the active state")
require(SERVICE, "isUserVisibleObservation", "tree-like operations must map to a single scan")
require(SERVICE, "showPointGesture", "resolved targets must map to point cues")
require(SERVICE, "showSwipeGesture", "actual swipe paths must map to palm cues")
require(VISUAL, "CURSOR_MOVE_MIN_MS = 180L", "cursor travel must have the agreed 180ms floor")
require(VISUAL, "CURSOR_MOVE_MAX_MS = 450L", "cursor travel must have the agreed 450ms cap")
require(VISUAL, "CURSOR_AFTERGLOW_MS = 240L", "action pose must retain a short afterglow")
require(VISUAL, "CURSOR_POINTER_HOLD_MS = 180L", "cursor must visibly return to the pointer pose")
require(VISUAL, "CURSOR_IDLE_DIM_MS = 420L", "idle pointer must ease into its dim state")
require(VISUAL, "CURSOR_RESTORE_MS = 120L", "the next movement must restore pointer visibility")
require(VISUAL, "CURSOR_IDLE_ALPHA = 0.18f", "idle pointer must remain present but unobtrusive")
require(VISUAL, "cursorPose = GLOVE_POSE_POINT;", "all cursor travel must use the default pointer")
require(VISUAL, "cursorFrame", "persistent cursor state must replace per-action glove effects")
require(SERVICE, "showBackGesture", "Back must map to a right-to-left cue")
require(SERVICE, "awaitCursorTravel", "real actions must wait for bounded cursor pre-travel")
require(SERVICE, "serializedControl", "HTTP and WS mutations must not race the singleton cursor")
require(SERVICE, "gestureDuration + 32", "scroll-until iterations must not overlap real swipe gestures")
require(SERVICE, "Looper.myLooper() == Looper.getMainLooper()", "cursor pre-travel must never sleep the main thread")
require(SERVICE, "candidate.authenticated && !candidate.closed", "only authenticated live sessions may show connected")
require(SERVICE, "connectionStatusOverlay.destroy();", "service teardown must remove the indicator")
require(SERVICE, 'boolParam(params, "includeIndicator", false)', "captures must exclude the indicator by default")
require(SERVICE, "indicatorRestored.compareAndSet", "capture restoration must be idempotent")
require(SERVICE, "restoreIndicator.run();", "all capture completion paths must restore the indicator")

print("connection activity overlay verifier passed")

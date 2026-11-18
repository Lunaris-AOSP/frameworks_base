# Fluid Cloud port audit

Inspected on 2026-11-18. Source dump: `/home/ghost/Music/cos`, package
`com.android.systemui`, version `17.99.02` (`179902`), compiled against SDK 37.
Lunaris framework checkout: `/home/ghost/fonts/base`, revision `bd723033b78c`.

The dump contains Fluid Cloud host code and several original card assets. It
does **not** contain the complete capsule renderer and its animations. A full
ColorOS mode for LunarisBar, with chip size as its only visual customization,
cannot be reproduced faithfully from this dump alone. No LunarisBar runtime or
settings changes were made during this audit.

## What the dump contains

| Component | Finding |
| --- | --- |
| Live Alert lifecycle and data forwarding | Present: `OplusLiveAlertViewModel`, `OplusLiveAlertAdapter`, `LiveAlertService`, and Seedling providers. |
| Status bar icon coordination | Present: `statusbar/seeding/anim/StatusBarCoordAnimator` and related animation controllers/data. These are host animations; their presence does not supply the missing capsule renderer. |
| Original media card assets | Present in `assets/MediaControl.upk`: declarative templates, styles, SVG icons, waveform effects, transport transitions, and Spotify controls. |
| Original flashlight card assets | Present in `assets/Flashlight.upk`: templates, styles, images, SVG icons, and open/close effects. |
| Ringer and DND card assets | Present in `assets/RingModeTip.upk` and `assets/DND.upk`. |
| Additional service cards | `assets/upks` contains Flashback AOD, Swiggy, and Zomato packages. This does not establish that all service templates are included. |
| Complete compact capsule design and transitions | Incomplete: the host delegates view creation to an external Seedling plugin. |
| Complete expanded cards, menus, and expand/collapse motion | Incomplete: plugin view creation and the plugin's `TemplateFactory` are missing. The included UPK templates cover only particular cards. |
| Original live service backend | Client interfaces and loaders are present; the external Pantanal UMS service package is absent from this dump. |

## Evidence for the missing implementation

Paths below are relative to `/home/ghost/Music/cos`.

- `smali_classes2/com/android/systemui/plugins/statusbar/SeedlingPlugin.smali`
  declares the plugin action `com.android.systemui.action.OPLUS_PLUGIN_SEEDLING`
  and abstract `onCreateView(int, ViewGroup)`. Its view types include status bar,
  keyguard status bar, notification panel, capsule card, and fake capsule.
- `smali_classes4/com/oplus/systemui/statusbar/seeding/CapsulePluginContainerController.smali`
  calls `SeedlingPlugin.onCreateView(...)` when the plugin is connected.
- `smali_classes4/com/oplus/systemui/statusbar/seeding/SeedlingPluginManager.smali`
  registers an external plugin listener and obtains `TemplateFactory` through
  `SeedlingPlugin.getProxy(...)`.
- `smali_classes4/com/oplus/systemui/statusbar/seeding/SeedlingCardProvider.smali`
  loads `com.oplus.systemui.plugins.seedling.SeedlingCardServerProvider` using
  the connected plugin context's class loader. That implementation is absent.
- `res/values/arrays.xml`, `config_pluginAllowlist`, includes the separate
  package `com.oplus.systemui.plugins`.
- An exhaustive search of all nine smali directories found no concrete class
  implementing `SeedlingPlugin` or `CapsulePlugin`, and no definitions under
  `com.oplus.systemui.plugins.seedling`.
- The original `/home/ghost/Music/cos.apk` was independently checked through
  its DEX class and interface tables: all nine DEX files, containing 73,315
  class definitions, likewise contain no Seedling/Capsule plugin implementation
  or definitions under the Seedling plugin provider and Pantanal UMS packages.
- `smali_classes4/com/oplus/systemui/statusbar/seeding/livealert/LiteUmsLoader.smali`
  calls the external authority
  `com.oplus.pantanal.ums.cardservice.provider.CardServiceClientProvider`.
  The manifest also queries `com.oplus.pantanal.ums`.

The APK manifests in `/home/ghost/Music` and
`/home/ghost/Downloads/Telegram Desktop` were inspected. Neither of those
locations contains the matching Oplus plugin or Pantanal UMS package.
`Music/plugin.apk` is `miui.systemui.plugin`. `FluidMotion.apk` is
`dev.flexy.fluidmotion`.

## Original assets that can already be studied

The four built-in UPK packages are ZIP archives containing `config.json`,
`assets/card-config.json`, and `pages/index.json`. Their interactive templates
use a design width of 328. This is a template coordinate system, not proof that
every expanded card should have a fixed physical width of 328 dp.

- Media supports `notification_sm`, `notification_md`, and `notification_lg`.
  Its waveform animations have 61 frames at 30 fps; the standard dark
  play/pause transitions have 19 frames at 60 fps.
- Flashlight supports `notification_sm` and `notification_lg`. Its open and
  close effects each have 15 frames at 30 fps.
- Ringer and DND support `notification_sm`; the ringer package includes
  distinct ring, silence, and vibrate animations.

These are content animations. They do not establish the complete capsule
appearance, switching, gesture, and expansion animation behavior.

## LunarisBar integration needed for the requested option

The existing `LunarisBarSettings.ANIMATION_STYLE_COLOR_OS = 2` selects
`ColorOsPopupMotion`; it is an animation setting. `LunarisBarPopupMotion` gives
the alternative animation path precedence. Expanded content still uses
LunarisBar or existing SystemUI cards, and the theme, geometry, shapes, and
per-feature overrides remain customizable. This is not a complete Fluid Cloud
mode.

A faithful implementation would need a separate bar-style selection and a
dedicated compact/expanded renderer using the recovered ColorOS templates and
animation behavior. The mode must use fixed ColorOS colors, shapes, artwork,
spacing, motion, gestures, and expanded layouts, while retaining chip
width/height controls. It must bypass visual and gesture overrides at runtime
as well as hiding their settings, and handle existing utility cards and
companion behavior according to the recovered ColorOS behavior.

Relevant integration points:

- `core/java/com/android/internal/util/lunaris/LunarisBarSettings.java`:
  persistent mode selection.
- `packages/SystemUI/src/com/android/systemui/statusbar/featurepods/popups/ui/compose/`:
  `LunarisBarTheme`, `LunarisBarContentTheme`, `LunarisBarChip`,
  `StatusBarDynamicIslandContainer`, `LunarisBarPopupMotion`, and
  `StatusBarPopup` for renderer, motion, and customization routing.
- `DynamicIslandFeatureSettings.WIDTH_SCALE` and `HEIGHT_SCALE`, collected by
  `rememberDynamicIslandSizeScale()`: existing chip size controls.
- `/home/ghost/lunar/singularity/src/org/lunaris/settings/fragments/statusbar/`:
  `DynamicIslandSettings`, `LunarisBarOptions`, and `LunarisBarEditor` for the
  style selector and customization availability.

The next required source is the matching `com.oplus.systemui.plugins` APK or
decoded dump from the same ColorOS build. Pantanal UMS is additionally relevant
if the scope includes original service integrations and its template runtime.
These components need an audit before claiming complete recoverable behavior;
visual and animation parity also requires comparison with the matching ColorOS
implementation running on a device.

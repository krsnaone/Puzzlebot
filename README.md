# 🎯 PuzzleBot — Android Number Puzzle Auto-Solver

An Android overlay app that sits **on top of your puzzle game**, scans the screen with ML Kit OCR, detects all numbered tiles, then auto-taps them in order (1 → last number) to solve the puzzle automatically.

---

## How It Works

```
Your Puzzle App  ←──── PuzzleBot overlay floats on top
       ↓
  Screen Capture (MediaProjection API)
       ↓
  ML Kit OCR detects all numbers + positions
       ↓
  Sort 1 → N
       ↓
  AccessibilityService injects tap gestures at each position
       ↓
  Puzzle Solved! ✓
```

No root required — uses Android's official Accessibility + MediaProjection APIs.

---

## Build Instructions

### Requirements
- Android Studio Hedgehog or newer
- Android SDK 34
- A physical Android device (API 26+) — emulators can't run MediaProjection well

### Steps

1. **Open project** in Android Studio
   ```
   File → Open → select the `PuzzleBot` folder
   ```

2. **Sync Gradle** (Android Studio will prompt you)

3. **Connect your Android phone** via USB, enable Developer Mode

4. **Run** the app (`▶` button or `Shift+F10`)

---

## First-Time Setup (one time only)

When you first launch PuzzleBot on your phone, you'll see 3 setup steps:

### Step 1 — Overlay Permission
Tap **"GRANT OVERLAY PERMISSION"** → it opens Android Settings → find PuzzleBot → toggle ON "Display over other apps"

### Step 2 — Accessibility Service
Tap **"ENABLE ACCESSIBILITY"** → Settings opens → scroll to find **PuzzleBot** → tap it → toggle **ON**

> This is what allows PuzzleBot to tap the screen for you without root.

### Step 3 — Set Tap Speed
Adjust the slider. Slower (600–1000ms) is safer for games that animate between taps. Faster (200ms) works for instant-response puzzles.

---

## Using PuzzleBot

1. Open your **puzzle game** and navigate to a puzzle
2. Open **PuzzleBot** → tap **▶ LAUNCH PUZZLEBOT**
3. A **screen record permission dialog** appears — tap **Start Now**
4. PuzzleBot minimizes — your puzzle app is back on screen
5. A small **green ▶ button** floats in the corner — drag it anywhere
6. Tap the **▶ button** to solve!

PuzzleBot will:
- Capture the current screen
- Find all numbers via OCR
- Draw glowing dots showing each position
- Tap them in sequence (1, 2, 3 … last)

The ▶ button shows live progress: `3/24 → #3`

---

## Troubleshooting

| Problem | Fix |
|--------|-----|
| Numbers not detected | Make sure the puzzle is fully visible, no loading screens |
| Wrong numbers detected | Some fonts confuse OCR — try a slightly longer delay or report the puzzle app |
| Taps miss the tiles | The game may have unusual screen scaling; try landscape mode |
| "Enable Accessibility" keeps showing | Go to Settings → Accessibility → Downloaded apps → PuzzleBot → ON |
| App crashes on capture | Tap the ▶ button again — first capture sometimes needs a retry |

---

## Permissions Explained

| Permission | Why |
|-----------|-----|
| `SYSTEM_ALERT_WINDOW` | Show the floating ▶ button over your puzzle app |
| `FOREGROUND_SERVICE` | Keep running while you're in another app |
| `AccessibilityService` | Inject screen taps (no root needed) |
| `MediaProjection` | Capture the screen to find numbers |

PuzzleBot does **not** record, store, or transmit your screen anywhere.

---

## Architecture

```
MainActivity.kt              — Setup UI, permission checks
OverlayService.kt            — Core: screen capture, OCR, overlay dots, tap sequence
PuzzleAccessibilityService.kt — Injects tap gestures via AccessibilityService API
DotOverlayView               — Full-screen transparent canvas drawing number dots
NumberResult.kt              — Data class: number + screen position
```

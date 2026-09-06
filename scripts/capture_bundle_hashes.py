#!/usr/bin/env python3
"""
Capture real perceptual hash values for PhonePe and WhatsApp bundle maps.

HOW THIS WORKS:
1. Connects to the iQOO 15 via ADB
2. Opens each target app and navigates to each screen
3. Takes a screenshot via ADB
4. Computes the 64-bit pHash using the same algorithm as PerceptualHasher.kt
5. Outputs updated JSON with real hash values

PREREQUISITES:
    pip install pillow adb-shell

USAGE:
    adb devices   # ensure iQOO 15 is connected
    python3 scripts/capture_bundle_hashes.py --app phonepe

NOTE: This is a HELPER script. The pHash values in phonepe_maps.json
are placeholders. Run this on an iQOO 15 with OriginOS 6 to get real values.
The Kotlin PerceptualHasher uses the same DCT-based 64-bit pHash algorithm
implemented below.
"""

import argparse
import json
import math
import os
import subprocess
import time
from pathlib import Path


def take_screenshot(output_path: str) -> bool:
    """Take a screenshot via ADB and save locally."""
    result = subprocess.run(
        ["adb", "exec-out", "screencap", "-p"],
        capture_output=True
    )
    if result.returncode != 0:
        print(f"  ADB screenshot failed: {result.stderr}")
        return False
    with open(output_path, "wb") as f:
        f.write(result.stdout)
    return True


def compute_phash(image_path: str, hash_size: int = 8, dct_size: int = 32) -> int:
    """
    Compute 64-bit perceptual hash matching PerceptualHasher.kt.
    Uses DCT-based pHash: resize → greyscale → 2D DCT → top-left → mean threshold.
    """
    try:
        from PIL import Image
        import numpy as np
    except ImportError:
        print("pip install pillow numpy")
        exit(1)

    img = Image.open(image_path).convert("L").resize((dct_size, dct_size), Image.LANCZOS)
    pixels = np.array(img, dtype=float)

    # 2D DCT
    def dct1d(x):
        n = len(x)
        result = np.zeros(n)
        for k in range(n):
            s = sum(x[i] * math.cos(math.pi * k * (2 * i + 1) / (2 * n)) for i in range(n))
            result[k] = s * (math.sqrt(1 / n) if k == 0 else math.sqrt(2 / n))
        return result

    dct = np.apply_along_axis(dct1d, 1, pixels)  # rows
    dct = np.apply_along_axis(dct1d, 0, dct)      # cols

    # Top-left hash_size × hash_size
    low_freq = dct[:hash_size, :hash_size].flatten()
    mean = (sum(low_freq) - low_freq[0]) / (len(low_freq) - 1)

    # Build 64-bit hash
    hash_bits = 0
    for i, val in enumerate(low_freq):
        if val >= mean:
            hash_bits |= (1 << i)

    return hash_bits


def capture_app_screens(app: str, screens: list[dict]) -> dict:
    """Navigate through app screens, capture screenshots, compute hashes."""
    results = {}
    for screen in screens:
        name = screen["name"]
        nav_cmd = screen.get("adb_cmd")

        if nav_cmd:
            print(f"  Navigating: {nav_cmd}")
            subprocess.run(["adb", "shell"] + nav_cmd.split(), capture_output=True)
            time.sleep(screen.get("wait_s", 2.0))

        screenshot_path = f"/tmp/lumi_{app}_{name}.png"
        print(f"  Capturing {name}...")
        if not take_screenshot(screenshot_path):
            continue

        phash = compute_phash(screenshot_path)
        results[name] = phash
        print(f"    pHash: {phash} (0x{phash:016x})")

    return results


PHONEPE_SCREENS = [
    {"name": "PhonePe_Home",           "adb_cmd": "monkey -p com.phonepe.app 1", "wait_s": 3.0},
    {"name": "PhonePe_RecipientEntry", "adb_cmd": None, "wait_s": 1.0},  # Manual: tap Pay
    {"name": "PhonePe_AmountEntry",    "adb_cmd": None, "wait_s": 1.0},
    {"name": "PhonePe_UPIPinEntry",    "adb_cmd": None, "wait_s": 1.0},
]

WHATSAPP_SCREENS = [
    {"name": "WhatsApp_ChatList",      "adb_cmd": "monkey -p com.whatsapp 1", "wait_s": 3.0},
    {"name": "WhatsApp_ChatWindow",    "adb_cmd": None, "wait_s": 1.0},
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", choices=["phonepe", "whatsapp", "all"], default="all")
    parser.add_argument("--maps-dir", default="app/src/main/assets/uimaps/")
    args = parser.parse_args()

    print("Checking ADB connection...")
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    if "device" not in result.stdout:
        print("No ADB device found. Connect iQOO 15 via USB with USB debugging enabled.")
        exit(1)

    if args.app in ("phonepe", "all"):
        print("\n== PhonePe ==")
        hashes = capture_app_screens("phonepe", PHONEPE_SCREENS)
        update_map_file(args.maps_dir + "phonepe_maps.json", hashes)

    if args.app in ("whatsapp", "all"):
        print("\n== WhatsApp ==")
        hashes = capture_app_screens("whatsapp", WHATSAPP_SCREENS)
        update_map_file(args.maps_dir + "whatsapp_maps.json", hashes)

    print("\nDone! Rebuild the APK to include updated bundle maps.")


def update_map_file(path: str, hashes: dict):
    if not Path(path).exists():
        print(f"Map file not found: {path}")
        return
    with open(path) as f:
        data = json.load(f)

    for screen in data.get("screens", []):
        label = screen.get("screenLabel")
        if label in hashes:
            old = screen["screenHash"]
            screen["screenHash"] = hashes[label]
            print(f"  Updated {label}: {old} → {hashes[label]}")

    with open(path, "w") as f:
        json.dump(data, f, indent=2)
    print(f"  Saved {path}")


if __name__ == "__main__":
    main()

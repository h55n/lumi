#!/usr/bin/env python3
"""
Generate Lumi pre-recorded audio files using gTTS (free) or ElevenLabs (optional).

Usage:
    pip install gtts pydub
    python3 scripts/generate_audio.py --out app/src/main/res/raw/

For higher quality, set ELEVENLABS_API_KEY:
    export ELEVENLABS_API_KEY=your_key
    python3 scripts/generate_audio.py --engine elevenlabs --out app/src/main/res/raw/
"""
import argparse
import os
import sys

CLIPS = {
    "audio_chime": None,  # Must be a sound file — see note below
    "audio_checking_en": "Let me check your screen",
    "audio_checking_hi": "Main dekh raha hoon",
    "audio_moment_en": "One moment",
    "audio_moment_hi": "Ek second",
    "audio_cancel_en": "Okay, I stopped",
    "audio_cancel_hi": "Theek hai, ruk gaya",
    "audio_done_en": "Done!",
    "audio_done_hi": "Ho gaya!",
}

LANG_MAP = {
    "_en": "en",
    "_hi": "hi",
}


def generate_gtts(clips: dict, out_dir: str):
    try:
        from gtts import gTTS
    except ImportError:
        print("Install gTTS: pip install gtts")
        sys.exit(1)

    os.makedirs(out_dir, exist_ok=True)
    for name, text in clips.items():
        if text is None:
            print(f"  SKIP {name} (chime — add manually)")
            continue
        lang = "en"
        for suffix, l in LANG_MAP.items():
            if name.endswith(suffix):
                lang = l
                break
        out_path = os.path.join(out_dir, f"{name}.mp3")
        print(f"  Generating {out_path} [{lang}]: '{text}'")
        tts = gTTS(text=text, lang=lang, slow=False)
        tts.save(out_path)
    print("\nDone. Copy files to app/src/main/res/raw/ and rename:")
    print("  audio_checking_en.mp3 → audio_checking.mp3  (or keep bilingual versions)")
    print("\nIMPORTANT: Add a chime sound (audio_chime.mp3) manually.")
    print("  Free sources: https://freesound.org (search 'soft chime')")


def generate_elevenlabs(clips: dict, out_dir: str):
    api_key = os.environ.get("ELEVENLABS_API_KEY")
    if not api_key:
        print("Set ELEVENLABS_API_KEY env var")
        sys.exit(1)

    try:
        from elevenlabs import ElevenLabs, VoiceSettings
        from elevenlabs import save
    except ImportError:
        print("Install ElevenLabs: pip install elevenlabs")
        sys.exit(1)

    client = ElevenLabs(api_key=api_key)
    os.makedirs(out_dir, exist_ok=True)

    voice_en = "Rachel"  # Warm, clear voice
    voice_hi = "Rachel"  # ElevenLabs has multilingual support

    for name, text in clips.items():
        if text is None:
            continue
        lang = "hi" if "_hi" in name else "en"
        voice = voice_hi if lang == "hi" else voice_en
        out_path = os.path.join(out_dir, f"{name}.mp3")
        print(f"  Generating {out_path} via ElevenLabs: '{text}'")
        audio = client.generate(text=text, voice=voice, model="eleven_multilingual_v2")
        save(audio, out_path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate Lumi audio clips")
    parser.add_argument("--engine", choices=["gtts", "elevenlabs"], default="gtts")
    parser.add_argument("--out", default="app/src/main/res/raw/")
    args = parser.parse_args()

    print(f"Generating audio with {args.engine} → {args.out}")
    if args.engine == "gtts":
        generate_gtts(CLIPS, args.out)
    else:
        generate_elevenlabs(CLIPS, args.out)

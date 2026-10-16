#!/usr/bin/env python3
"""Synthesize the stalker's "extended fart plus wet noise" cue.

This is a build-time tool, not shipped with the mod: it renders
assets/stalker/sounds/voice_fart.ogg from scratch so the sound is fully
reproducible and carries no third-party audio licensing. Run it only when the
sound design changes:

    python3 tools/gen_fart.py

Design (a ~2 s, single long fart with wet gurgles under it):
  * body    - a low sawtooth-ish drone whose pitch glides downward, run through
              a gently sweeping low-pass; this is the "fart" fundamental.
  * bubble  - a few short band-passed noise bursts at irregular times, the wet
              gurgle layer riding under the drone.
  * texture - a constant low-level noise bed so it reads as breathy, not a
              clean tone.

All randomness is seeded so repeated runs produce the identical file.
"""

import os
import struct

import numpy as np
import soundfile as sf

SEED = 0xF17  # fixed so the committed .ogg is reproducible
SAMPLE_RATE = 44100
DURATION_S = 2.0
OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src",
    "main",
    "resources",
    "assets",
    "stalker",
    "sounds",
    "voice_fart.ogg",
)


def one_pole_lowpass(signal, cutoff_hz, sample_rate):
    """Time-varying one-pole low-pass; cutoff_hz may be an array."""
    cutoff = np.atleast_1d(np.asarray(cutoff_hz, dtype=np.float64))
    if cutoff.size == 1:
        cutoff = np.full(signal.shape, cutoff[0])
    alpha = 1.0 - np.exp(-2.0 * np.pi * cutoff / sample_rate)
    out = np.empty_like(signal)
    acc = 0.0
    for i in range(signal.size):
        acc += alpha[i] * (signal[i] - acc)
        out[i] = acc
    return out


def bandpass_noise(rng, n, low_hz, high_hz, sample_rate):
    """Rough band-passed white noise via a difference of two low-passed copies."""
    noise = rng.standard_normal(n)
    low = one_pole_lowpass(noise, low_hz, sample_rate)
    high = one_pole_lowpass(noise, high_hz, sample_rate)
    return high - low


def main():
    rng = np.random.default_rng(SEED)
    n = int(SAMPLE_RATE * DURATION_S)
    t = np.arange(n) / SAMPLE_RATE

    # ---- 1. fart body: downward-gliding drone -------------------------------
    # The glide is what makes it read as a fart rather than a note: start high,
    # sag through the middle, settle low. Phase is the integral of frequency.
    f0 = 140.0
    f_end = 62.0
    sag = np.interp(t / DURATION_S, [0.0, 0.35, 1.0], [f0, f0 * 0.82, f_end])
    # A slow wobble on top, the flutter of the "motor".
    wobble = (
        1.0
        + 0.035 * np.sin(2.0 * np.pi * 6.5 * t)
        + 0.02 * np.sin(2.0 * np.pi * 11.0 * t + 1.1)
    )
    freq = sag * wobble
    phase = 2.0 * np.pi * np.cumsum(freq) / SAMPLE_RATE

    # Sawtooth-ish stack: fundamental plus a couple of harmonics for buzz.
    body = (
        np.sin(phase)
        + 0.55 * np.sin(2.0 * phase)
        + 0.30 * np.sin(3.0 * phase + 0.4)
        + 0.15 * np.sin(5.0 * phase + 0.9)
    )

    # Sweep the low-pass down with the pitch so the whole thing darkens as it
    # descends - the "running out of air" end of a real fart.
    cutoff = np.interp(t / DURATION_S, [0.0, 1.0], [900.0, 320.0])
    body = one_pole_lowpass(body, cutoff, SAMPLE_RATE)

    # ---- 2. wet gurgles: irregular band-passed noise bursts -----------------
    wet = np.zeros(n)
    for start_s, length_s, lo, hi, gain in [
        (0.18, 0.16, 220.0, 900.0, 0.55),
        (0.44, 0.22, 180.0, 700.0, 0.70),
        (0.63, 0.12, 300.0, 1200.0, 0.45),
        (0.91, 0.28, 150.0, 650.0, 0.80),
        (1.20, 0.18, 240.0, 950.0, 0.50),
        (1.44, 0.30, 130.0, 600.0, 0.65),
        (1.72, 0.20, 200.0, 800.0, 0.40),
    ]:
        start = int(start_s * SAMPLE_RATE)
        length = int(length_s * SAMPLE_RATE)
        if start + length > n:
            length = n - start
        burst = bandpass_noise(rng, length, lo, hi, SAMPLE_RATE)
        # Percussive envelope so each gurgle plops rather than hisses flat.
        env = np.exp(-np.linspace(0.0, 5.0, length))
        wet[start : start + length] += burst * env * gain

    # ---- 3. breathy noise bed + envelope ------------------------------------
    texture = rng.standard_normal(n)
    texture = one_pole_lowpass(texture, 400.0, SAMPLE_RATE) * 0.10
    texture *= np.interp(t / DURATION_S, [0.0, 0.5, 1.0], [0.6, 1.0, 0.4])

    mix = body * 0.75 + wet + texture

    # Overall envelope: quick attack, long body, gentle release, with a final
    # sputter as it dies.
    fade_in = np.clip(t / 0.06, 0.0, 1.0)
    fade_out = np.clip((DURATION_S - t) / 0.35, 0.0, 1.0)
    sputter = 1.0 - 0.25 * np.clip((t - 1.55) / 0.45, 0.0, 1.0) * (
        0.5 + 0.5 * np.sin(2.0 * np.pi * 9.0 * t)
    )
    mix *= fade_in * fade_out * sputter

    # Normalise to a comfortable headroom, then trim DC.
    mix -= np.mean(mix)
    peak = np.max(np.abs(mix))
    if peak > 0:
        mix = mix / peak * 0.89

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    sf.write(OUT, mix.astype(np.float32), SAMPLE_RATE, format="OGG", subtype="VORBIS")
    print(
        f"wrote {OUT} ({os.path.getsize(OUT)} bytes, {DURATION_S}s @ {SAMPLE_RATE}Hz)"
    )


if __name__ == "__main__":
    main()

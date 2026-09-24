#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FlashAlarm - BIDSleep Multi-Night Dataset Ingestion & Epoch Alignment
====================================================================
Processes 47 subjects (Bidslab00 - Bidslab68), 253 nights of raw multimodal recordings:
  - hr.csv: Instantaneous PPG/Apple Watch heart rate (~0.2Hz - 1Hz)
  - motion.csv: High-frequency wrist accelerometry (~50Hz, streaming chunked)
  - labels.mat: PSG ground-truth sleep stages (AASM: 0=Wake, 1=N1, 2=N2, 3=N3, 4=REM, 5=Unknown)
    and recStart in US Eastern Time ('America/New_York').

Output:
  - rem_ml_trainer/bidsleep_epochs.csv: Aligned 30s epoch statistics per night.
"""

import os
import sys
import glob
import time
import argparse
from datetime import datetime
import pytz
import numpy as np
import pandas as pd
import scipy.io
from concurrent.futures import ProcessPoolExecutor, as_completed

DEFAULT_DATA_DIR = r"F:\night\a-multi-night-instantaneous-heart-rate-and-accelerometry-dataset-with-eeg-sleep-stage-labels-1.0.0"
EASTERN_TZ = pytz.timezone("America/New_York")


def parse_single_night(subject_id: str, night_id: str, night_dir: str):
    """
    Parse and align hr.csv, motion.csv, and labels.mat for one night.
    Returns a pandas DataFrame of valid 30s epochs, or None if invalid.
    """
    hr_path = os.path.join(night_dir, "hr.csv")
    motion_path = os.path.join(night_dir, "motion.csv")
    mat_path = os.path.join(night_dir, "labels.mat")

    if not (os.path.exists(hr_path) and os.path.exists(motion_path) and os.path.exists(mat_path)):
        return None

    # 1. Load labels.mat & recStart
    try:
        mat = scipy.io.loadmat(mat_path)
        rec_str = str(mat["recStart"][0]).strip()
        dt_naive = datetime.strptime(rec_str, "%Y-%m-%d %H:%M:%S")
        dt_eastern = EASTERN_TZ.localize(dt_naive)
        rec_start_unix = dt_eastern.timestamp()

        # Prioritize expert_label, fallback to dreem_label
        stages = None
        if "expert_label" in mat and mat["expert_label"].size > 0:
            exp_arr = mat["expert_label"].flatten()
            if not np.all(exp_arr == 0):
                stages = exp_arr
        if stages is None and "dreem_label" in mat and mat["dreem_label"].size > 0:
            drm_arr = mat["dreem_label"].flatten()
            if not np.all(drm_arr == 0):
                stages = drm_arr

        if stages is None or len(stages) < 60:
            return None

    except Exception as e:
        return None

    n_epochs = len(stages)

    # Find sleep onset and offset (AASM sleep stages: 1, 2, 3, 4)
    sleep_indices = np.where((stages >= 1) & (stages <= 4))[0]
    if len(sleep_indices) < 30:
        return None
    onset_epoch = int(sleep_indices[0])
    offset_epoch = int(sleep_indices[-1])

    # 2. Parse hr.csv
    try:
        df_hr = pd.read_csv(hr_path, header=None, names=["time", "hr"], dtype={"time": np.float64, "hr": np.float32})
        df_hr = df_hr[(df_hr["hr"] >= 35.0) & (df_hr["hr"] <= 220.0)]
        df_hr["epoch"] = np.floor((df_hr["time"] - rec_start_unix) / 30.0).astype(np.int32)
        df_hr = df_hr[(df_hr["epoch"] >= onset_epoch) & (df_hr["epoch"] <= offset_epoch)]

        hr_grp = df_hr.groupby("epoch")["hr"].agg(
            hr_mean="mean",
            hr_std=lambda x: float(np.std(x, ddof=0)) if len(x) > 1 else 0.0,
            hr_count="count"
        )
    except Exception as e:
        return None

    # 3. Stream motion.csv in chunks to prevent OOM
    acc_sums = np.zeros(n_epochs, dtype=np.float64)
    acc_counts = np.zeros(n_epochs, dtype=np.int32)
    acc_peaks = np.zeros(n_epochs, dtype=np.float32)

    try:
        for chunk in pd.read_csv(
            motion_path,
            chunksize=150000,
            dtype={"Timestamp": np.float64, "x": np.float32, "y": np.float32, "z": np.float32}
        ):
            t = chunk["Timestamp"].values
            ep = np.floor((t - rec_start_unix) / 30.0).astype(np.int32)
            valid_mask = (ep >= onset_epoch) & (ep <= offset_epoch)
            if not np.any(valid_mask):
                continue

            ep_v = ep[valid_mask]
            x = chunk["x"].values[valid_mask]
            y = chunk["y"].values[valid_mask]
            z = chunk["z"].values[valid_mask]
            vm = np.sqrt(x * x + y * y + z * z)
            vm_dev = np.abs(vm - 1.0).astype(np.float32)

            # Vectorized sum and count via np.bincount
            acc_sums += np.bincount(ep_v, weights=vm_dev, minlength=n_epochs)
            acc_counts += np.bincount(ep_v, minlength=n_epochs)

            # Fast group max
            df_chunk_v = pd.DataFrame({"epoch": ep_v, "vm_dev": vm_dev})
            max_chunk = df_chunk_v.groupby("epoch")["vm_dev"].max()
            for e_idx, p_val in max_chunk.items():
                if p_val > acc_peaks[e_idx]:
                    acc_peaks[e_idx] = p_val

    except Exception as e:
        return None

    # 4. Construct epoch records
    records = []
    for ep in range(onset_epoch, offset_epoch + 1):
        stg = int(stages[ep])
        if stg > 4:  # Skip unknown/unassigned/artifact (stage 5)
            continue

        if ep not in hr_grp.index:
            continue
        hr_row = hr_grp.loc[ep]
        if hr_row["hr_count"] < 2:
            continue

        m_count = acc_counts[ep]
        if m_count < 10:
            continue

        m_mean = float(acc_sums[ep] / m_count)
        m_peak = float(acc_peaks[ep])

        time_since_onset_min = float((ep - onset_epoch) * 0.5)

        records.append({
            "subject_id": subject_id,
            "night_id": night_id,
            "epoch_idx": ep,
            "epoch_rel_onset": ep - onset_epoch,
            "time_since_onset_min": time_since_onset_min,
            "stage": stg,
            "is_rem": 1 if stg == 4 else 0,
            "hr_mean": float(hr_row["hr_mean"]),
            "hr_std": float(hr_row["hr_std"]),
            "hr_count": int(hr_row["hr_count"]),
            "motion_mean": m_mean,
            "motion_peak": m_peak,
            "motion_count": int(m_count)
        })

    if len(records) < 50:
        return None

    return pd.DataFrame(records)


def collect_night_tasks(data_dir: str):
    """Scan all Bidslab subjects and nights."""
    tasks = []
    subjects = sorted([d for d in os.listdir(data_dir) if d.startswith("Bidslab")])
    for sub in subjects:
        sub_dir = os.path.join(data_dir, sub)
        if not os.path.isdir(sub_dir):
            continue
        for night in sorted(os.listdir(sub_dir)):
            night_dir = os.path.join(sub_dir, night)
            if not os.path.isdir(night_dir):
                continue
            tasks.append((sub, night, night_dir))
    return tasks


def main():
    parser = argparse.ArgumentParser(description="Prepare and align BIDSleep multi-night dataset.")
    parser.add_argument("--data_dir", type=str, default=DEFAULT_DATA_DIR, help="Path to BIDSleep dataset root")
    parser.add_argument("--output_file", type=str, default="bidsleep_epochs.csv", help="Output CSV path")
    parser.add_argument("--workers", type=int, default=8, help="Number of parallel worker processes")
    args = parser.parse_args()

    print("=" * 70)
    print("  BIDSleep Multimodal Dataset Alignment & Preprocessing")
    print(f"  Dataset: {args.data_dir}")
    print(f"  Output:  {args.output_file}")
    print(f"  Workers: {args.workers}")
    print("=" * 70)

    t0 = time.time()
    tasks = collect_night_tasks(args.data_dir)
    print(f"[+] Discovered {len(tasks)} night directories across {len(set(t[0] for t in tasks))} subjects.")

    all_dfs = []
    success_count = 0
    fail_count = 0

    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(parse_single_night, sub, night, ndir): (sub, night) for sub, night, ndir in tasks}
        total_tasks = len(futures)
        done_count = 0

        for future in as_completed(futures):
            sub, night = futures[future]
            done_count += 1
            try:
                df_night = future.result()
                if df_night is not None and len(df_night) > 0:
                    all_dfs.append(df_night)
                    success_count += 1
                else:
                    fail_count += 1
            except Exception as e:
                fail_count += 1

            if done_count % 25 == 0 or done_count == total_tasks:
                elapsed = time.time() - t0
                pct = done_count / total_tasks * 100
                print(f"  [{done_count:3d}/{total_tasks}] ({pct:5.1f}%) | Success: {success_count}, Skipped: {fail_count} | Elapsed: {elapsed:.1f}s")

    if not all_dfs:
        print("[!] No valid nights were processed. Exiting.")
        sys.exit(1)

    print("\n[+] Concatenating all processed nights...")
    df_all = pd.concat(all_dfs, ignore_index=True)

    # Sort strictly by subject, night, epoch
    df_all = df_all.sort_values(["subject_id", "night_id", "epoch_idx"]).reset_index(drop=True)

    print(f"[+] Dataset Summary:")
    print(f"    Total aligned epochs: {len(df_all):,}")
    print(f"    Unique subjects:      {df_all['subject_id'].nunique()}")
    print(f"    Unique nights:        {success_count}")
    print(f"    Stage distribution:")
    for stg, cnt in df_all["stage"].value_counts().sort_index().items():
        stg_name = {0: "Wake", 1: "N1", 2: "N2", 3: "N3", 4: "REM"}.get(stg, "Other")
        print(f"      Stage {stg} ({stg_name:5s}): {cnt:7,d} ({cnt/len(df_all)*100:5.1f}%)")

    # Save output
    output_path = os.path.abspath(args.output_file)
    os.makedirs(os.path.dirname(output_path) if os.path.dirname(output_path) else ".", exist_ok=True)
    df_all.to_csv(output_path, index=False)
    file_size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"\n[+] Saved to {output_path} ({file_size_mb:.2f} MB)")
    print(f"[+] Total execution time: {time.time() - t0:.2f} seconds.")


if __name__ == "__main__":
    main()

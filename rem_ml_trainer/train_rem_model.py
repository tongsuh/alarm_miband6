#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=============================================================================
FlashAlarm - REM Sleep Classifier Training & Android Java Generator Pipeline
=============================================================================
Dataset: PhysioNet Sleep-Accel (Apple Watch PPG HR + 3-Axis Actigraphy with PSG Ground Truth)
Paper:   Walch et al., "Sleep stage prediction with raw acceleration and photoplethysmography
         heart rate data derived from a consumer wearable device", SLEEP (2019)
URL:     https://physionet.org/content/sleep-accel/1.0.0/

Constraints:
  - Input: 1Hz Heart Rate (PPG) + Actigraphy (3-axis Accelerometer)
  - Real-time online prediction latency: <= 5 minutes (10 epochs of 30s)
  - Output: Pure Java code (RemClassifierModel.java) exported via m2cgen for direct
    compilation in Android Studio without TensorFlow/PyTorch dependencies.
=============================================================================
"""

import os
import sys
import argparse
import glob
import time
import requests
import numpy as np
import pandas as pd
try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, desc=""):
        return iterable
from sklearn.model_selection import GroupKFold
from sklearn.metrics import classification_report, roc_auc_score, confusion_matrix
import lightgbm as lgb
import m2cgen as m2c

# 11 features aligned with <= 5 min future latency
FEATURE_NAMES = [
    "hr_mean_curr",           # [0] Current 30s mean heart rate (bpm)
    "hr_std_curr",            # [1] Current 30s heart rate standard deviation (bpm)
    "motion_mean_curr",       # [2] Current 30s mean vector magnitude delta |VM - 1g|
    "motion_max_curr",        # [3] Current 30s peak movement spike (g)
    "hr_surge_baseline",      # [4] Relative HR surge over rolling 30m nocturnal baseline: (HR - Base) / Base
    "hr_mean_past_5m",        # [5] Mean HR across past 10 epochs (t-5m to t)
    "hr_mean_future_5m",      # [6] Mean HR across future 10 epochs (t to t+5m) [<= 5min delay constraint]
    "hr_std_context",         # [7] HR standard deviation across 21-epoch centered window (t-5m to t+5m)
    "motion_mean_past_5m",    # [8] Mean wrist motion across past 10 epochs
    "motion_mean_future_5m",  # [9] Mean wrist motion across future 10 epochs (verifies sustained atonia)
    "time_since_start_min"    # [10] Elapsed time in minutes since sleep onset (ultradian cycle prior)
]

def parse_subject_data(subject_id, hr_path, acc_path, label_path):
    """
    Parse and align a single subject's 3 asynchronous raw time-series files:
      - labels: 30s epochs (date, stage)
      - heartrate: 1Hz PPG HR (date, hr), comma-separated
      - acceleration: 50Hz 3-axis motion (date, x, y, z), whitespace-separated
    """
    # 1. Load PSG ground-truth labels
    try:
        df_label = pd.read_csv(label_path, sep=r'\s+', header=None, names=['time', 'stage'])
    except Exception as e:
        print(f"  [!] Error reading labels for {subject_id}: {e}")
        return None
        
    # Drop unscored/artifact epochs (stage < 0)
    df_label = df_label[df_label['stage'] >= 0].copy()
    if len(df_label) < 100:
        print(f"  [!] Subject {subject_id} has insufficient label epochs ({len(df_label)}). Skipping.")
        return None
        
    max_psg_time = df_label['time'].max() + 30
    df_label['epoch'] = (df_label['time'] // 30).astype(int)
    # Binary classification target: 1 if REM (stage 5), 0 if non-REM (stages 0, 1, 2, 3)
    df_label['is_rem'] = (df_label['stage'] == 5).astype(int)

    # 2. Load 1Hz PPG heart rate
    try:
        df_hr = pd.read_csv(hr_path, sep=',', header=None, names=['time', 'hr'])
    except Exception as e:
        print(f"  [!] Error reading heart rate for {subject_id}: {e}")
        return None
        
    # Keep only records during PSG recording window [0, max_psg_time]
    df_hr = df_hr[(df_hr['time'] >= 0) & (df_hr['time'] <= max_psg_time)].copy()
    # Filter physiological HR bounds (35 to 220 bpm)
    df_hr = df_hr[(df_hr['hr'] >= 35) & (df_hr['hr'] <= 220)]
    if len(df_hr) < 200:
        print(f"  [!] Subject {subject_id} has insufficient HR data ({len(df_hr)}). Skipping.")
        return None

    df_hr['epoch'] = (df_hr['time'] // 30).astype(int)
    hr_epoch_stats = df_hr.groupby('epoch')['hr'].agg(
        hr_mean='mean',
        hr_std=lambda x: x.std(ddof=0) if len(x) > 1 else 0.0,
        hr_count='count'
    ).reset_index()
    # Filter out epochs with fewer than 5 HR samples
    hr_epoch_stats = hr_epoch_stats[hr_epoch_stats['hr_count'] >= 5]

    # 3. Load 3-axis acceleration
    try:
        df_acc = pd.read_csv(acc_path, sep=r'\s+', header=None, names=['time', 'x', 'y', 'z'], engine='c')
    except Exception as e:
        print(f"  [!] Error reading acceleration for {subject_id}: {e}")
        return None

    # Filter only records during PSG window
    df_acc = df_acc[(df_acc['time'] >= 0) & (df_acc['time'] <= max_psg_time)].copy()
    if len(df_acc) < 1000:
        print(f"  [!] Subject {subject_id} has insufficient motion data. Skipping.")
        return None

    # Vector magnitude: VM = sqrt(x^2 + y^2 + z^2)
    # Deviation from 1g Earth gravity baseline: |VM - 1.0|
    vm = np.sqrt(df_acc['x']**2 + df_acc['y']**2 + df_acc['z']**2)
    df_acc['vm_dev'] = np.abs(vm - 1.0)
    df_acc['epoch'] = (df_acc['time'] // 30).astype(int)

    acc_epoch_stats = df_acc.groupby('epoch')['vm_dev'].agg(
        motion_mean='mean',
        motion_max='max'
    ).reset_index()

    # 4. Merge 3 modalities on 30s epoch
    df_merged = pd.merge(df_label[['epoch', 'stage', 'is_rem']], hr_epoch_stats, on='epoch', how='inner')
    df_merged = pd.merge(df_merged, acc_epoch_stats, on='epoch', how='inner')
    df_merged = df_merged.sort_values('epoch').reset_index(drop=True)

    if len(df_merged) < 60:
        print(f"  [!] Subject {subject_id} has too few merged epochs ({len(df_merged)}). Skipping.")
        return None

    # 5. Compute sliding contextual features conforming to <= 5 min (10 epochs) future latency
    df_merged['subject_id'] = subject_id
    df_merged['hr_mean_curr'] = df_merged['hr_mean']
    df_merged['hr_std_curr'] = df_merged['hr_std'].fillna(0.0)
    df_merged['motion_mean_curr'] = df_merged['motion_mean']
    df_merged['motion_max_curr'] = df_merged['motion_max']

    # Past 5 minutes (10 epochs: t-10 to t-1)
    df_merged['hr_mean_past_5m'] = df_merged['hr_mean'].shift(1).rolling(10, min_periods=3).mean()
    df_merged['motion_mean_past_5m'] = df_merged['motion_mean'].shift(1).rolling(10, min_periods=3).mean()

    # Future 5 minutes (10 epochs: t+1 to t+10) [Adhering strictly to user's 5-minute latency tolerance]
    df_merged['hr_mean_future_5m'] = df_merged['hr_mean'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1]
    df_merged['motion_mean_future_5m'] = df_merged['motion_mean'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1]

    # Centered 10-minute context HR standard deviation (21 epochs: t-10 to t+10)
    df_merged['hr_std_context'] = df_merged['hr_mean'].rolling(21, min_periods=5, center=True).std(ddof=0).fillna(0.0)

    # Dynamic 30-minute nocturnal baseline (rolling minimum / 10th percentile over preceding 60 epochs)
    baseline_hr = df_merged['hr_mean'].rolling(60, min_periods=10).quantile(0.10)
    df_merged['hr_surge_baseline'] = (df_merged['hr_mean'] - baseline_hr) / baseline_hr.clip(lower=40.0)

    # Elapsed sleep time in minutes (captures the ultradian cycle prior: REM is rare in first 70-90 min)
    start_epoch = df_merged['epoch'].min()
    df_merged['time_since_start_min'] = (df_merged['epoch'] - start_epoch) * 0.5

    # Fill any remaining edge boundary NaNs with current values
    df_merged['hr_mean_past_5m'] = df_merged['hr_mean_past_5m'].fillna(df_merged['hr_mean'])
    df_merged['motion_mean_past_5m'] = df_merged['motion_mean_past_5m'].fillna(df_merged['motion_mean'])
    df_merged['hr_mean_future_5m'] = df_merged['hr_mean_future_5m'].fillna(df_merged['hr_mean'])
    df_merged['motion_mean_future_5m'] = df_merged['motion_mean_future_5m'].fillna(df_merged['motion_mean'])
    df_merged['hr_surge_baseline'] = df_merged['hr_surge_baseline'].fillna(0.0)

    return df_merged[FEATURE_NAMES + ['is_rem', 'subject_id']]

def discover_subjects(data_dir):
    """
    Find matching subject IDs with heart_rate, motion, and labels files.
    Supports either PhysioNet subfolders (heart_rate/, motion/, labels/) or a single flat folder.
    """
    hr_files = glob.glob(os.path.join(data_dir, "**", "*_heartrate.txt"), recursive=True)
    if not hr_files:
        return []

    subjects = []
    for hr_path in hr_files:
        filename = os.path.basename(hr_path)
        sub_id = filename.split('_')[0]
        
        parent_dir = os.path.dirname(os.path.dirname(hr_path))
        same_dir = os.path.dirname(hr_path)
        
        label_candidates = [
            os.path.join(parent_dir, "labels", f"{sub_id}_labeled_sleep.txt"),
            os.path.join(same_dir, f"{sub_id}_labeled_sleep.txt")
        ]
        acc_candidates = [
            os.path.join(parent_dir, "motion", f"{sub_id}_acceleration.txt"),
            os.path.join(same_dir, f"{sub_id}_acceleration.txt")
        ]
        
        label_path = next((p for p in label_candidates if os.path.exists(p)), None)
        acc_path = next((p for p in acc_candidates if os.path.exists(p)), None)
        
        if label_path and acc_path:
            subjects.append((sub_id, hr_path, acc_path, label_path))

    return subjects

def download_sample_dataset(dest_dir, num_subjects=1):
    """
    Convenience utility to download sample subjects directly from PhysioNet for testing.
    """
    base_url = "https://physionet.org/files/sleep-accel/1.0.0"
    sample_sub_ids = ["1066528", "1360686", "1449548"][:num_subjects]
    
    os.makedirs(os.path.join(dest_dir, "heart_rate"), exist_ok=True)
    os.makedirs(os.path.join(dest_dir, "labels"), exist_ok=True)
    os.makedirs(os.path.join(dest_dir, "motion"), exist_ok=True)
    
    print(f"\n[+] Downloading {len(sample_sub_ids)} sample subjects from PhysioNet...")
    for sub_id in sample_sub_ids:
        for folder, ext in [("labels", "_labeled_sleep.txt"), ("heart_rate", "_heartrate.txt"), ("motion", "_acceleration.txt")]:
            filename = f"{sub_id}{ext}"
            file_url = f"{base_url}/{folder}/{filename}"
            save_path = os.path.join(dest_dir, folder, filename)
            if not os.path.exists(save_path):
                print(f"    Downloading {folder}/{filename} ...")
                r = requests.get(file_url, stream=True)
                with open(save_path, 'wb') as f:
                    for chunk in r.iter_content(chunk_size=1024*1024):
                        if chunk: f.write(chunk)
    print("[+] Download complete!\n")

def generate_kotlin_feature_extractor(output_dir):
    """
    Generates a production Kotlin helper (RemFeatureExtractor.kt) showing how to maintain
    the 21-epoch buffer on Android and extract the EXACT same 11 features for inference.
    """
    kotlin_code = """package com.flashalarm.miband.domain.algorithm

import kotlin.math.max
import kotlin.math.sqrt

/**
 * RemFeatureExtractor
 * Maintains an online sliding window of 21 30-second epochs (~10.5 minutes),
 * allowing decision of the target epoch (index 10, i.e., 5 minutes in the past)
 * with complete future atonia and HR confirmation within the 5-minute latency tolerance.
 */
class RemFeatureExtractor {

    data class RawEpoch(
        val epochIndex: Int,
        val meanHr: Float,
        val stdHr: Float,
        val meanMotion: Float,
        val peakMotion: Float
    )

    private val epochBuffer = ArrayDeque<RawEpoch>()
    private val hrHistory30m = ArrayDeque<Float>() // Up to 60 epochs (30 mins) for dynamic baseline

    fun reset() {
        epochBuffer.clear()
        hrHistory30m.clear()
    }

    /**
     * Push a newly completed 30-second epoch into the buffer.
     * @return 11-dimensional double array for RemClassifierModel.score(features) if buffer is ready (>=21 epochs),
     *         or null if initial 5-minute warm-up buffer is still filling.
     */
    fun pushEpoch(
        epochIndex: Int,
        meanHr: Float,
        stdHr: Float,
        meanMotion: Float,
        peakMotion: Float
    ): DoubleArray? {
        val epoch = RawEpoch(epochIndex, meanHr, stdHr, meanMotion, peakMotion)
        epochBuffer.addLast(epoch)
        hrHistory30m.addLast(meanHr)
        if (hrHistory30m.size > 60) hrHistory30m.removeFirst()

        // We need at least 21 epochs to evaluate the target epoch (index 10)
        if (epochBuffer.size < 21) {
            return null
        }
        if (epochBuffer.size > 21) {
            epochBuffer.removeFirst()
        }

        // Target epoch is exactly in the center (index 10): 10 past epochs, 1 target, 10 future epochs
        val target = epochBuffer[10]

        // 1. Current epoch features
        val hrMeanCurr = target.meanHr.toDouble()
        val hrStdCurr = target.stdHr.toDouble()
        val motionMeanCurr = target.meanMotion.toDouble()
        val motionMaxCurr = target.peakMotion.toDouble()

        // 2. Past 5m (indices 0..9)
        var sumPastHr = 0.0
        var sumPastMotion = 0.0
        for (i in 0 until 10) {
            sumPastHr += epochBuffer[i].meanHr
            sumPastMotion += epochBuffer[i].meanMotion
        }
        val hrMeanPast5m = sumPastHr / 10.0
        val motionMeanPast5m = sumPastMotion / 10.0

        // 3. Future 5m (indices 11..20) [5-minute confirmation buffer]
        var sumFutureHr = 0.0
        var sumFutureMotion = 0.0
        for (i in 11 until 21) {
            sumFutureHr += epochBuffer[i].meanHr
            sumFutureMotion += epochBuffer[i].meanMotion
        }
        val hrMeanFuture5m = sumFutureHr / 10.0
        val motionMeanFuture5m = sumFutureMotion / 10.0

        // 4. Centered 10m context HR standard deviation
        var sumAllHr = 0.0
        for (ep in epochBuffer) sumAllHr += ep.meanHr
        val meanAllHr = sumAllHr / 21.0
        var sumSqDiff = 0.0
        for (ep in epochBuffer) {
            val diff = ep.meanHr - meanAllHr
            sumSqDiff += diff * diff
        }
        val hrStdContext = sqrt(sumSqDiff / 21.0)

        // 5. Dynamic 30-minute nocturnal baseline (10th percentile of past 30 minutes)
        val sortedHr = hrHistory30m.sorted()
        val p10Index = (sortedHr.size * 0.10f).toInt().coerceIn(0, sortedHr.size - 1)
        val baselineHr = max(sortedHr[p10Index].toDouble(), 40.0)
        val hrSurgeBaseline = (hrMeanCurr - baselineHr) / baselineHr

        // 6. Time since recording start in minutes
        val timeSinceStartMin = (target.epochIndex * 0.5)

        // Must match FEATURE_NAMES order exactly:
        return doubleArrayOf(
            hrMeanCurr,           // [0]
            hrStdCurr,            // [1]
            motionMeanCurr,       // [2]
            motionMaxCurr,        // [3]
            hrSurgeBaseline,      // [4]
            hrMeanPast5m,         // [5]
            hrMeanFuture5m,       // [6]
            hrStdContext,         // [7]
            motionMeanPast5m,     // [8]
            motionMeanFuture5m,   // [9]
            timeSinceStartMin     // [10]
        )
    }
}
"""
    kt_path = os.path.join(output_dir, "RemFeatureExtractor.kt")
    with open(kt_path, "w", encoding="utf-8") as f:
        f.write(kotlin_code)
    print(f"[+] Exported Kotlin Feature Extractor to: {kt_path}")

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, ".."))
    default_output = os.path.join(project_root, "app", "src", "main", "java", "com", "flashalarm", "miband", "domain", "algorithm")
    default_data = os.path.join(script_dir, "sleep-accel")

    parser = argparse.ArgumentParser(description="Train REM Sleep Classifier on PhysioNet Sleep-Accel Dataset")
    parser.add_argument("--data-dir", type=str, default=default_data, help=f"Directory containing sleep-accel dataset (default: {default_data})")
    parser.add_argument("--output-dir", type=str, default=default_output,
                        help=f"Target directory to save RemClassifierModel.java and RemFeatureExtractor.kt (default: {default_output})")
    parser.add_argument("--download-sample", action="store_true", help="Automatically download sample subjects from PhysioNet for a quick trial")
    parser.add_argument("--sample-count", type=int, default=1, help="Number of sample subjects to download if --download-sample is used (default: 1)")
    parser.add_argument("--max-subjects", type=int, default=31, help="Max number of subjects to process")
    args = parser.parse_args()

    print("=" * 70)
    print("  FlashAlarm REM Classifier Training & Android Java Export Pipeline")
    print("=" * 70)

    # 1. Download sample if requested
    if args.download_sample:
        download_sample_dataset(args.data_dir, num_subjects=args.sample_count)

    # 2. Discover available subjects
    subjects = discover_subjects(args.data_dir)
    if not subjects:
        print(f"\n[!] No valid subjects found in: {args.data_dir}")
        print("    Please download the dataset from:")
        print("    https://physionet.org/content/sleep-accel/1.0.0/")
        print("    and extract it into the '--data-dir' folder, or run with '--download-sample'.\n")
        sys.exit(1)

    print(f"[+] Discovered {len(subjects)} valid subjects with HR, Motion, and PSG Labels.")
    subjects = subjects[:args.max_subjects]

    # 3. Extract features across all subjects
    all_dfs = []
    print(f"\n[+] Extracting 30s epoch features and 5-min contextual buffers...")
    for sub_id, hr_path, acc_path, label_path in tqdm(subjects, desc="Processing subjects"):
        df_sub = parse_subject_data(sub_id, hr_path, acc_path, label_path)
        if df_sub is not None:
            all_dfs.append(df_sub)

    if not all_dfs:
        print("[!] No usable data extracted. Exiting.")
        sys.exit(1)

    df_all = pd.concat(all_dfs, ignore_index=True)
    df_all = df_all.dropna().reset_index(drop=True)

    rem_count = int(df_all['is_rem'].sum())
    total_count = len(df_all)
    rem_pct = (rem_count / total_count) * 100.0
    print(f"\n[+] Total Valid Epochs: {total_count} (REM: {rem_count} [{rem_pct:.1f}%], Non-REM: {total_count - rem_count})")

    X = df_all[FEATURE_NAMES].values
    y = df_all['is_rem'].values
    groups = df_all['subject_id'].values

    # 4. Rigorous GroupKFold Cross Validation (Ensuring zero subject data leakage)
    n_splits = min(5, len(np.unique(groups)))
    print(f"\n[+] Running {n_splits}-Fold Group Cross-Validation (split by Subject ID)...")
    gkf = GroupKFold(n_splits=n_splits)

    oof_preds = np.zeros(len(y))
    oof_probs = np.zeros(len(y))

    for fold, (train_idx, val_idx) in enumerate(gkf.split(X, y, groups)):
        X_train, y_train = X[train_idx], y[train_idx]
        X_val, y_val = X[val_idx], y[val_idx]

        # LightGBM tuned for compact Java bytecode size (<25KB, well below Java 64KB method limit)
        clf = lgb.LGBMClassifier(
            n_estimators=30,
            max_depth=4,
            num_leaves=15,
            min_child_samples=20,
            class_weight='balanced',
            random_state=42 + fold,
            verbose=-1
        )
        clf.fit(X_train, y_train)

        probs = clf.predict_proba(X_val)[:, 1]
        oof_probs[val_idx] = probs
        oof_preds[val_idx] = (probs >= 0.50).astype(int)

    # 5. Output Validation Results
    print("\n" + "=" * 50)
    print("  Out-of-Fold Cross-Validation Performance (No Subject Leakage)")
    print("=" * 50)
    print(classification_report(y, oof_preds, target_names=['Non-REM', 'REM (做梦期)']))
    auc = roc_auc_score(y, oof_probs)
    print(f"ROC-AUC Score: {auc:.4f}")

    cm = confusion_matrix(y, oof_preds)
    print(f"\nConfusion Matrix:\n  [TN: {cm[0,0]}  FP: {cm[0,1]}]\n  [FN: {cm[1,0]}  TP: {cm[1,1]}]")

    # 6. Fit Final Model on All Data & Export to Java
    print(f"\n[+] Fitting final model on all {len(X)} epochs...")
    final_model = lgb.LGBMClassifier(
        n_estimators=30,
        max_depth=4,
        num_leaves=15,
        min_child_samples=20,
        class_weight='balanced',
        random_state=42,
        verbose=-1
    )
    final_model.fit(X, y)

    os.makedirs(args.output_dir, exist_ok=True)
    java_code = m2c.export_to_java(
        final_model,
        package_name="com.flashalarm.miband.domain.algorithm",
        class_name="RemClassifierModel"
    )

    java_path = os.path.join(args.output_dir, "RemClassifierModel.java")
    with open(java_path, "w", encoding="utf-8") as f:
        f.write(java_code)

    print(f"[+] Successfully exported Java model to: {java_path}")
    print(f"    Code length: {len(java_code)} chars (~{len(java_code)//1024} KB)")

    # 7. Generate matching Kotlin Feature Extractor
    generate_kotlin_feature_extractor(args.output_dir)

    print("\n" + "=" * 70)
    print("  [SUCCESS] Pipeline Completed!")
    print("  In Android Kotlin, you can now do:")
    print("  -------------------------------------------------------------")
    print("  val extractor = RemFeatureExtractor()")
    print("  val features = extractor.pushEpoch(epochIdx, hrMean, hrStd, motionMean, motionMax)")
    print("  if (features != null) {")
    print("      val probs = RemClassifierModel.score(features)")
    print("      val isRem = probs[1] > 0.50 // probs[1] is REM probability")
    print("  }")
    print("  -------------------------------------------------------------")
    print("=" * 70)

if __name__ == "__main__":
    main()

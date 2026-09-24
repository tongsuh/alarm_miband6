#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FlashAlarm - BIDSleep 16-Feature Multimodal REM Model Trainer
=============================================================
Trains a production-grade LightGBM REM classifier on BIDSleep multimodal dataset
(47 subjects, 253 nights). Features strictly follow physiological first principles:
  0: hr_mean_curr
  1: hr_std_curr
  2: motion_mean_curr
  3: motion_peak_curr
  4: hr_surge_slowwave_baseline
  5: hr_mean_past_5m
  6: hr_mean_future_3m
  7: hr_step_contrast
  8: hr_turbulence_1hz
  9: motion_mean_past_5m
  10: motion_future_atonia_ratio
  11: circadian_cycle_sin
  12: circadian_cycle_cos
  13: time_since_onset_min
  14: hr_std_context_10m
  15: motion_entropy_context

Validation:
  - GroupKFold (5 splits, strictly grouped by subject_id)
  - Full diagnostic metrics: ROC-AUC, PR-AUC, Confusion Matrix, Precision/Recall curves
Export:
  - Pure Java RemClassifierModel.java via m2cgen (<64KB JVM bytecode)
"""

import os
import sys
import argparse
import numpy as np
import pandas as pd
from sklearn.model_selection import GroupKFold
from sklearn.metrics import (
    roc_auc_score,
    average_precision_score,
    confusion_matrix,
    classification_report
)
import lightgbm as lgb
import m2cgen as m2c

FEATURE_NAMES = [
    "hr_mean_curr",               # [0] Current 30s epoch mean HR
    "hr_std_curr",                # [1] Current 30s epoch HR std dev
    "motion_mean_curr",           # [2] Current 30s epoch mean |VM - 1g|
    "motion_peak_curr",           # [3] Current 30s epoch peak acceleration dev
    "hr_surge_slowwave_baseline", # [4] Relative HR surge over 30-60m 10% quantile baseline
    "hr_mean_past_5m",            # [5] Past 5m (10 epochs) mean HR
    "hr_mean_future_3m",          # [6] Future 3m (6 epochs) mean HR
    "hr_step_contrast",           # [7] Future 3m mean - Past 5m mean HR
    "hr_turbulence_1hz",          # [8] 1Hz pulse local dispersion coefficient (std / mean)
    "motion_mean_past_5m",        # [9] Past 5m (10 epochs) mean motion
    "motion_future_atonia_ratio", # [10] Future 3m wrist stillness/atonia ratio (<0.045g)
    "circadian_cycle_sin",        # [11] sin(2pi * t / 90)
    "circadian_cycle_cos",        # [12] cos(2pi * t / 90)
    "time_since_onset_min",       # [13] Minutes elapsed since sleep onset
    "hr_std_context_10m",         # [14] HR std dev across 17-epoch context window
    "motion_entropy_context"      # [15] 5-bin Shannon entropy of motion in context window
]


def compute_night_features(df_night: pd.DataFrame) -> pd.DataFrame:
    """
    Extracts the 16 physiological features for a single subject night.
    Requires at least 25 consecutive epochs.
    """
    df = df_night.sort_values("epoch_idx").copy().reset_index(drop=True)
    if len(df) < 25:
        return pd.DataFrame()

    hr_series = df["hr_mean"].astype(np.float64)
    motion_series = df["motion_mean"].astype(np.float64)

    # 0: hr_mean_curr
    df["hr_mean_curr"] = hr_series
    # 1: hr_std_curr
    df["hr_std_curr"] = df["hr_std"].astype(np.float64)
    # 2: motion_mean_curr
    df["motion_mean_curr"] = motion_series
    # 3: motion_peak_curr
    df["motion_peak_curr"] = df["motion_peak"].astype(np.float64)

    # 4: hr_surge_slowwave_baseline (relative surge over rolling 30-60m 10th percentile)
    # Window of 60 epochs (30 min)
    rolling_baseline = hr_series.rolling(60, min_periods=10).quantile(0.10).bfill()
    clamped_base = rolling_baseline.clip(lower=40.0)
    df["hr_surge_slowwave_baseline"] = (hr_series - clamped_base) / clamped_base

    # 5: hr_mean_past_5m (past 10 epochs: t-10 to t-1)
    df["hr_mean_past_5m"] = hr_series.shift(1).rolling(10, min_periods=3).mean()

    # 6: hr_mean_future_3m (future 6 epochs: t+1 to t+6)
    df["hr_mean_future_3m"] = hr_series.iloc[::-1].shift(1).rolling(6, min_periods=2).mean().iloc[::-1]

    # 7: hr_step_contrast (future 3m mean - past 5m mean)
    df["hr_step_contrast"] = df["hr_mean_future_3m"] - df["hr_mean_past_5m"]

    # 8: hr_turbulence_1hz (local dispersion coefficient: std / mean)
    df["hr_turbulence_1hz"] = df["hr_std_curr"] / (df["hr_mean_curr"] + 1e-5)

    # 9: motion_mean_past_5m (past 10 epochs: t-10 to t-1)
    df["motion_mean_past_5m"] = motion_series.shift(1).rolling(10, min_periods=3).mean()

    # 10: motion_future_atonia_ratio (future 6 epochs with motion < 0.045g)
    is_atonia = (motion_series < 0.045).astype(np.float64)
    df["motion_future_atonia_ratio"] = is_atonia.iloc[::-1].shift(1).rolling(6, min_periods=2).mean().iloc[::-1]

    # 11 & 12: circadian cycle sin & cos (90-minute ultradian rhythm)
    t_min = df["time_since_onset_min"].astype(np.float64)
    angle = 2.0 * np.pi * (t_min / 90.0)
    df["circadian_cycle_sin"] = np.sin(angle)
    df["circadian_cycle_cos"] = np.cos(angle)

    # 13: time_since_onset_min
    df["time_since_onset_min"] = t_min

    # 14: hr_std_context_10m (centered 17-epoch context window: t-10 to t+6)
    df["hr_std_context_10m"] = hr_series.rolling(17, min_periods=5, center=True).std(ddof=0).fillna(0.0)

    # 15: motion_entropy_context (5-bin Shannon entropy across 17-epoch window)
    # Bins: [0, 0.01), [0.01, 0.03), [0.03, 0.07), [0.07, 0.15), [0.15, inf)
    b0 = (motion_series < 0.01).astype(np.float64).rolling(17, center=True, min_periods=3).sum()
    b1 = ((motion_series >= 0.01) & (motion_series < 0.03)).astype(np.float64).rolling(17, center=True, min_periods=3).sum()
    b2 = ((motion_series >= 0.03) & (motion_series < 0.07)).astype(np.float64).rolling(17, center=True, min_periods=3).sum()
    b3 = ((motion_series >= 0.07) & (motion_series < 0.15)).astype(np.float64).rolling(17, center=True, min_periods=3).sum()
    b4 = (motion_series >= 0.15).astype(np.float64).rolling(17, center=True, min_periods=3).sum()

    n_bins = b0 + b1 + b2 + b3 + b4
    entropy = np.zeros(len(df), dtype=np.float64)
    for b in [b0, b1, b2, b3, b4]:
        p = np.where(n_bins > 0, b / n_bins, 0.0)
        p_safe = np.where(p > 0, p, 1.0)
        entropy += np.where(p > 0, - p * np.log(p_safe), 0.0)
    df["motion_entropy_context"] = entropy

    # Discard boundary epochs (first 10 and last 6 epochs per night)
    # to guarantee full 17-epoch context matching Android online inference
    df_valid = df.iloc[10:-6].copy()

    # Fill any remaining NaNs gracefully
    df_valid["hr_mean_past_5m"] = df_valid["hr_mean_past_5m"].fillna(df_valid["hr_mean_curr"])
    df_valid["hr_mean_future_3m"] = df_valid["hr_mean_future_3m"].fillna(df_valid["hr_mean_curr"])
    df_valid["hr_step_contrast"] = df_valid["hr_step_contrast"].fillna(0.0)
    df_valid["motion_mean_past_5m"] = df_valid["motion_mean_past_5m"].fillna(df_valid["motion_mean_curr"])
    df_valid["motion_future_atonia_ratio"] = df_valid["motion_future_atonia_ratio"].fillna(1.0)
    df_valid["hr_surge_slowwave_baseline"] = df_valid["hr_surge_slowwave_baseline"].fillna(0.0)

    return df_valid


def main():
    parser = argparse.ArgumentParser(description="Train 16-feature multimodal REM sleep classifier.")
    parser.add_argument("--epochs_csv", type=str, default="rem_ml_trainer/bidsleep_epochs.csv", help="Input epochs CSV")
    parser.add_argument("--output_java", type=str, default="app/src/main/java/com/flashalarm/miband/domain/algorithm/RemClassifierModel.java", help="Path to write Java model")
    parser.add_argument("--backup_java", type=str, default="rem_ml_trainer/RemClassifierModel.java", help="Backup Java path")
    args = parser.parse_args()

    print("=" * 70)
    print("  FlashAlarm - BIDSleep 16-Feature Multimodal REM Classifier")
    print(f"  Input Dataset: {args.epochs_csv}")
    print(f"  Target Output: {args.output_java}")
    print("=" * 70)

    if not os.path.exists(args.epochs_csv):
        print(f"[!] Error: {args.epochs_csv} does not exist. Run prepare_bidsleep_dataset.py first.")
        sys.exit(1)

    print(f"[+] Loading epoch data from {args.epochs_csv}...")
    df_raw = pd.read_csv(args.epochs_csv)
    print(f"    Loaded {len(df_raw):,} epochs from {df_raw['subject_id'].nunique()} subjects.")

    # 1. Feature Engineering per night
    print("[+] Extracting 16 physiological features per subject and night...")
    night_groups = df_raw.groupby(["subject_id", "night_id"])
    feature_dfs = []
    for (sub_id, night_id), df_night in night_groups:
        feat_df = compute_night_features(df_night)
        if len(feat_df) > 0:
            feature_dfs.append(feat_df)

    df_all = pd.concat(feature_dfs, ignore_index=True)
    print(f"[+] Feature engineering completed: {len(df_all):,} valid epochs.")
    n_rem = int(df_all["is_rem"].sum())
    n_non_rem = len(df_all) - n_rem
    print(f"    REM Epochs:     {n_rem:,} ({n_rem / len(df_all) * 100:.1f}%)")
    print(f"    Non-REM Epochs: {n_non_rem:,} ({n_non_rem / len(df_all) * 100:.1f}%)")
    print(f"    Imbalance ratio (Non-REM : REM) = {n_non_rem / max(1, n_rem):.2f} : 1")

    X = df_all[FEATURE_NAMES].values
    y = df_all["is_rem"].values
    groups = df_all["subject_id"].values

    # 2. 5-Fold GroupKFold Cross-Validation (by subject_id)
    n_splits = 5
    print(f"\n[+] Running {n_splits}-Fold GroupKFold Cross-Validation (split strictly by subject_id)...")
    gkf = GroupKFold(n_splits=n_splits)

    oof_probs = np.zeros(len(y), dtype=np.float64)
    fold_aucs = []
    fold_praucs = []

    for fold, (train_idx, val_idx) in enumerate(gkf.split(X, y, groups), 1):
        y_train = y[train_idx]
        n_pos = np.sum(y_train == 1)
        n_neg = np.sum(y_train == 0)
        scale_pos = float(n_neg) / max(1.0, float(n_pos))

        clf = lgb.LGBMClassifier(
            n_estimators=45,
            max_depth=4,
            num_leaves=15,
            min_child_samples=40,
            learning_rate=0.06,
            subsample=0.85,
            colsample_bytree=0.85,
            scale_pos_weight=scale_pos,
            random_state=42 + fold,
            verbose=-1
        )
        clf.fit(X[train_idx], y[train_idx])

        val_probs = clf.predict_proba(X[val_idx])[:, 1]
        oof_probs[val_idx] = val_probs

        val_auc = roc_auc_score(y[val_idx], val_probs)
        val_prauc = average_precision_score(y[val_idx], val_probs)
        fold_aucs.append(val_auc)
        fold_praucs.append(val_prauc)

        val_subs = len(np.unique(groups[val_idx]))
        print(f"  Fold {fold} ({val_subs} subjects): Validation ROC-AUC = {val_auc:.4f}, PR-AUC = {val_prauc:.4f}")

    overall_auc = roc_auc_score(y, oof_probs)
    overall_prauc = average_precision_score(y, oof_probs)
    print("\n" + "=" * 60)
    print("  Out-of-Fold Cross-Validation Overall Evaluation (Subject-Grouped)")
    print("=" * 60)
    print(f"  Mean 5-Fold ROC-AUC: {np.mean(fold_aucs):.4f} +/- {np.std(fold_aucs):.4f}")
    print(f"  Mean 5-Fold PR-AUC:  {np.mean(fold_praucs):.4f} +/- {np.std(fold_praucs):.4f}")
    print(f"  Overall ROC-AUC:     {overall_auc:.4f}")
    print(f"  Overall PR-AUC:      {overall_prauc:.4f}")

    print("\n  Threshold Scan Performance:")
    print("  " + "-" * 56)
    print("  Threshold | REM Precision | REM Recall | REM F1  | Spec (Non-REM)")
    print("  " + "-" * 56)
    for th in [0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70]:
        preds = (oof_probs >= th).astype(int)
        tn, fp, fn, tp = confusion_matrix(y, preds).ravel()
        prec = tp / max(1, (tp + fp)) * 100.0
        rec = tp / max(1, (tp + fn)) * 100.0
        f1 = 2 * prec * rec / max(1e-5, (prec + rec)) / 100.0
        spec = tn / max(1, (tn + fp)) * 100.0
        print(f"    {th:5.2f}   |    {prec:5.1f}%    |   {rec:5.1f}%  |  {f1:5.3f}  |    {spec:5.1f}%")
    print("  " + "-" * 56)

    # 3. Fit Final Production Model on 100% of Data
    print(f"\n[+] Fitting final production model on all {len(X):,} epochs...")
    n_pos_all = np.sum(y == 1)
    n_neg_all = np.sum(y == 0)
    final_model = lgb.LGBMClassifier(
        n_estimators=45,
        max_depth=4,
        num_leaves=15,
        min_child_samples=40,
        learning_rate=0.06,
        subsample=0.85,
        colsample_bytree=0.85,
        scale_pos_weight=float(n_neg_all) / max(1.0, float(n_pos_all)),
        random_state=42,
        verbose=-1
    )
    final_model.fit(X, y)

    print("\n[+] Top Feature Importances (Gain / Splits):")
    feature_imp = sorted(zip(FEATURE_NAMES, final_model.feature_importances_), key=lambda x: x[1], reverse=True)
    for rank, (name, imp) in enumerate(feature_imp, 1):
        print(f"  {rank:2d}. {name:30s}: {imp:5d}")

    # 4. Export Pure Java Code using m2cgen
    print(f"\n[+] Exporting pure Java model to {args.output_java} via m2cgen...")
    java_code = m2c.export_to_java(
        final_model,
        package_name="com.flashalarm.miband.domain.algorithm",
        class_name="RemClassifierModel"
    )

    # Ensure parent dir exists
    os.makedirs(os.path.dirname(os.path.abspath(args.output_java)), exist_ok=True)
    with open(args.output_java, "w", encoding="utf-8") as f:
        f.write(java_code)
    print(f"[+] Successfully wrote {args.output_java} ({len(java_code):,} chars, ~{len(java_code)//1024} KB)")

    if args.backup_java:
        os.makedirs(os.path.dirname(os.path.abspath(args.backup_java)), exist_ok=True)
        with open(args.backup_java, "w", encoding="utf-8") as f:
            f.write(java_code)
        print(f"[+] Successfully wrote backup to {args.backup_java}")

    print("\n" + "=" * 70)
    print("  [SUCCESS] 16-Feature Multimodal REM Model Training Completed!")
    print("=" * 70)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FlashAlarm True-HRV REM Sleep Model Trainer
============================================
Trains a clinically-grounded LightGBM decision-tree model using true millisecond-accurate
R-R intervals (IBI) from PhysioNet MIT-BIH Polysomnographic Database (slpdb).

Features Engineered (30-second epochs):
  - mean_rr: Mean RR interval in milliseconds
  - hr: Mean Heart Rate (bpm)
  - rmssd: Root Mean Square of Successive Differences (ms, parasympathetic vagal marker)
  - sdnn: Standard Deviation of NN intervals (ms, total autonomic variance)
  - pnn50: Percentage of successive intervals differing by > 50 ms (%)
  - cv_rr: Coefficient of variation (sdnn / mean_rr)
  - hr_surge: Relative heart rate acceleration above local baseline
  - autonomic_balance: Ratio of SDNN to RMSSD (proxy for sympathetic/vagal tone)
  - rmssd_ratio: Current RMSSD relative to 5-minute past rolling mean
  - hr_past_5m, hr_future_5m: 5-minute temporal contextual heart rate
  - rmssd_past_5m, rmssd_future_5m: 5-minute temporal contextual RMSSD
  - Dynamic subject Z-scores for inter-individual baseline invariance

Output:
  - Pure Java: RemHrvClassifierModel.java (zero external dependencies, <64KB bytecode)
  - Kotlin Helper: RemHrvFeatureExtractor.kt
"""

import os
import sys
import argparse
import collections
import numpy as np
import pandas as pd
from sklearn.model_selection import GroupKFold
from sklearn.metrics import classification_report, roc_auc_score, confusion_matrix
import lightgbm as lgb
import m2cgen as m2c
import wfdb

ALL_SLPDB_RECORDS = [
    'slp01a', 'slp01b', 'slp02a', 'slp02b', 'slp03', 'slp04',
    'slp14', 'slp16', 'slp32', 'slp37', 'slp41', 'slp45',
    'slp48', 'slp59', 'slp60', 'slp61', 'slp66'
]

FS = 250.0  # slpdb ECG sampling frequency (Hz)


def ensure_dataset_cached(cache_dir: str):
    """Downloads slpdb annotation files from PhysioNet if not already cached."""
    os.makedirs(cache_dir, exist_ok=True)
    for rec in ALL_SLPDB_RECORDS:
        st_file = os.path.join(cache_dir, f"{rec}.st")
        ecg_file = os.path.join(cache_dir, f"{rec}.ecg")
        if not (os.path.exists(st_file) and os.path.exists(ecg_file)):
            print(f"[+] Downloading {rec} annotations from PhysioNet slpdb...", flush=True)
            try:
                st = wfdb.rdann(rec, 'st', pn_dir='slpdb')
                ecg = wfdb.rdann(rec, 'ecg', pn_dir='slpdb')
                wfdb.wrann(rec, 'st', st.sample, st.symbol, aux_note=st.aux_note, write_dir=cache_dir)
                wfdb.wrann(rec, 'ecg', ecg.sample, ecg.symbol, aux_note=ecg.aux_note, write_dir=cache_dir)
            except Exception as e:
                print(f"[-] Error downloading {rec}: {e}", flush=True)
        else:
            # Already cached
            pass
    print(f"[+] All {len(ALL_SLPDB_RECORDS)} records verified in cache: {cache_dir}")


def extract_epochs(cache_dir: str) -> pd.DataFrame:
    """Extracts 30-second epoch HRV features and ground-truth sleep stages."""
    all_rows = []

    for rec in ALL_SLPDB_RECORDS:
        st_path = os.path.join(cache_dir, rec)
        if not os.path.exists(st_path + ".st"):
            continue

        st = wfdb.rdann(st_path, 'st')
        ecg = wfdb.rdann(st_path, 'ecg')

        st_samples = st.sample
        # Clean null-bytes and whitespace from annotations
        st_notes = [note.replace('\x00', '').strip().split()[0] for note in st.aux_note if note]
        ecg_samples = ecg.sample
        sub_id = rec[:5]

        for i in range(min(len(st_samples) - 1, len(st_notes))):
            t_start = st_samples[i]
            t_end = st_samples[i + 1]
            stage = st_notes[i]

            # In sleep medicine, ignore movement time (MT) and unclassified (?)
            if stage in ['?', 'MT', 'M']:
                continue

            # Ground truth: REM vs Non-REM (1, 2, 3, 4)
            # When focusing on sleep periods, exclude active Wake ('W')
            is_rem = 1 if stage == 'R' else 0

            # Extract cardiologist-validated R-peaks within this 30s epoch
            epoch_r_peaks = ecg_samples[(ecg_samples >= t_start) & (ecg_samples < t_end)]
            if len(epoch_r_peaks) < 8:
                continue

            # Calculate RR intervals in milliseconds
            rrs = np.diff(epoch_r_peaks) / FS * 1000.0
            # Physiological bandpass filter: 300ms (200 bpm) to 2000ms (30 bpm)
            rrs = rrs[(rrs >= 300) & (rrs <= 2000)]
            if len(rrs) < 5:
                continue

            mean_rr = float(np.mean(rrs))
            sdnn = float(np.std(rrs))
            diff_rrs = np.diff(rrs)
            rmssd = float(np.sqrt(np.mean(diff_rrs ** 2))) if len(diff_rrs) > 0 else 0.0
            pnn50 = float(np.mean(np.abs(diff_rrs) > 50.0) * 100.0) if len(diff_rrs) > 0 else 0.0
            cv_rr = (sdnn / mean_rr) if mean_rr > 0 else 0.0

            all_rows.append({
                'subject_id': sub_id,
                'record': rec,
                'epoch': i,
                'is_rem': is_rem,
                'stage': stage,
                'mean_rr': mean_rr,
                'hr': 60000.0 / mean_rr,
                'rmssd': rmssd,
                'sdnn': sdnn,
                'pnn50': pnn50,
                'cv_rr': cv_rr
            })

    return pd.DataFrame(all_rows)


def build_features(df: pd.DataFrame) -> pd.DataFrame:
    """Computes temporal context windows and baseline-invariant Z-scores."""
    feature_dfs = []

    for rec, rec_df in df.groupby('record'):
        rec_df = rec_df.sort_values('epoch').copy()

        # 5-minute past rolling windows (10 epochs of 30s)
        rec_df['rmssd_past_5m'] = rec_df['rmssd'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['rmssd'])
        rec_df['hr_past_5m'] = rec_df['hr'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['hr'])
        rec_df['sdnn_past_5m'] = rec_df['sdnn'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['sdnn'])
        rec_df['cv_past_5m'] = rec_df['cv_rr'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['cv_rr'])

        # 5-minute future rolling windows (within 5-min latency window)
        rec_df['rmssd_future_5m'] = rec_df['rmssd'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1].fillna(rec_df['rmssd'])
        rec_df['hr_future_5m'] = rec_df['hr'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1].fillna(rec_df['hr'])

        # Baseline HR tracking (10th percentile over past 30 mins)
        base_hr = rec_df['hr'].rolling(60, min_periods=10).quantile(0.10).fillna(rec_df['hr'].min())
        rec_df['hr_surge'] = (rec_df['hr'] - base_hr) / base_hr.clip(lower=40.0)

        # Autonomic balance proxy: SDNN / RMSSD
        rec_df['autonomic_balance'] = rec_df['sdnn'] / rec_df['rmssd'].clip(lower=1.0)
        rec_df['rmssd_ratio'] = rec_df['rmssd'] / rec_df['rmssd_past_5m'].clip(lower=1.0)

        # Inter-individual baseline invariance: Z-score normalization per night
        for col in ['mean_rr', 'hr', 'rmssd', 'sdnn', 'pnn50', 'cv_rr', 'hr_surge', 'autonomic_balance']:
            m = rec_df[col].mean()
            s = rec_df[col].std() + 1e-6
            rec_df[col + '_z'] = (rec_df[col] - m) / s

        # Rolling windows on normalized metrics
        for col in ['hr_z', 'rmssd_z', 'cv_rr_z', 'autonomic_balance_z']:
            rec_df[col + '_past_5m'] = rec_df[col].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df[col])
            rec_df[col + '_future_5m'] = rec_df[col].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1].fillna(rec_df[col])

        feature_dfs.append(rec_df)

    return pd.concat(feature_dfs, ignore_index=True).dropna()


FEATURE_COLUMNS = [
    'mean_rr_z',
    'hr_z',
    'rmssd_z',
    'sdnn_z',
    'pnn50_z',
    'cv_rr_z',
    'hr_surge_z',
    'autonomic_balance_z',
    'hr_z_past_5m',
    'hr_z_future_5m',
    'rmssd_z_past_5m',
    'rmssd_z_future_5m',
    'cv_rr_z_past_5m',
    'cv_rr_z_future_5m',
    'autonomic_balance_z_past_5m',
    'autonomic_balance_z_future_5m'
]


def generate_kotlin_extractor() -> str:
    """Generates pure Kotlin RemHrvFeatureExtractor that feeds RemHrvClassifierModel."""
    return """package com.flashalarm.miband.domain.algorithm

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time True-HRV Feature Extractor for FlashAlarm.
 * Buffers millisecond R-R intervals from ESP32-C3 / Standard BLE Heart Rate Service (0x180D/0x2A37).
 *
 * Maintains:
 *   - 30-second epoch R-R interval window
 *   - Overnight rolling baseline (mean & std) for subject-adaptive Z-score normalization
 *   - 5-minute past & future temporal smoothing windows (21-epoch ring buffer)
 */
class RemHrvFeatureExtractor {

    companion object {
        const val EPOCH_DURATION_MS = 30_000L
        const val WINDOW_SIZE = 21       // 10 past + 1 current + 10 future epochs (~10.5 minutes)
        const val TARGET_INDEX = 10      // The center epoch evaluated (5-min latency allowance)
    }

    data class EpochHrvRaw(
        val epochIndex: Long,
        val meanRr: Double,
        val hr: Double,
        val rmssd: Double,
        val sdnn: Double,
        val pnn50: Double,
        val cvRr: Double,
        val hrSurge: Double,
        val autonomicBalance: Double
    )

    private val currentEpochRrs = ArrayList<Double>()
    private var epochStartTimeMs: Long = 0L
    private var currentEpochIndex: Long = 0L

    // Overnight running statistics for Z-score normalization
    private var statCount = 0
    private var sumMeanRr = 0.0
    private var sumSqMeanRr = 0.0
    private var sumHr = 0.0
    private var sumSqHr = 0.0
    private var sumRmssd = 0.0
    private var sumSqRmssd = 0.0
    private var sumSdnn = 0.0
    private var sumSqSdnn = 0.0
    private var sumPnn50 = 0.0
    private var sumSqPnn50 = 0.0
    private var sumCv = 0.0
    private var sumSqCv = 0.0
    private var minHrTracked = 200.0

    // 21-epoch buffer for past/future rolling temporal context
    private val buffer = ArrayDeque<EpochHrvRaw>()

    /**
     * Push a newly arrived millisecond R-R interval from BLE.
     * @param rrMs R-R interval in milliseconds (e.g. 850.0 ms)
     * @param timestampMs current system timestamp in ms
     * @return 16-element feature array for RemHrvClassifierModel.score(), or null if buffering
     */
    @Synchronized
    fun pushRrInterval(rrMs: Double, timestampMs: Long): DoubleArray? {
        // Physiological bandpass filter (30 to 200 bpm)
        if (rrMs < 300.0 || rrMs > 2000.0) return null

        if (epochStartTimeMs == 0L) {
            epochStartTimeMs = timestampMs
        }

        currentEpochRrs.add(rrMs)

        // Check if 30-second epoch is complete
        if (timestampMs - epochStartTimeMs >= EPOCH_DURATION_MS) {
            val epoch = completeEpoch(currentEpochIndex++)
            currentEpochRrs.clear()
            epochStartTimeMs = timestampMs

            if (epoch != null) {
                buffer.addLast(epoch)
                if (buffer.size > WINDOW_SIZE) {
                    buffer.removeFirst()
                }

                if (buffer.size == WINDOW_SIZE) {
                    return extractFeatures()
                }
            }
        }
        return null
    }

    private fun completeEpoch(epochIdx: Long): EpochHrvRaw? {
        if (currentEpochRrs.size < 5) return null

        val n = currentEpochRrs.size
        val meanRr = currentEpochRrs.average()
        val hr = 60000.0 / meanRr

        var varSum = 0.0
        for (rr in currentEpochRrs) {
            varSum += (rr - meanRr).pow(2)
        }
        val sdnn = sqrt(varSum / n)

        var diffSqSum = 0.0
        var count50 = 0
        for (i in 0 until n - 1) {
            val diff = currentEpochRrs[i + 1] - currentEpochRrs[i]
            diffSqSum += diff * diff
            if (kotlin.math.abs(diff) > 50.0) {
                count50++
            }
        }
        val rmssd = if (n > 1) sqrt(diffSqSum / (n - 1)) else 0.0
        val pnn50 = if (n > 1) (count50.toDouble() / (n - 1)) * 100.0 else 0.0
        val cvRr = if (meanRr > 0) sdnn / meanRr else 0.0

        // Track baseline
        minHrTracked = min(minHrTracked, hr)
        val hrSurge = (hr - minHrTracked) / max(40.0, minHrTracked)
        val autonomicBalance = sdnn / max(1.0, rmssd)

        // Update running online Z-score statistics
        statCount++
        sumMeanRr += meanRr
        sumSqMeanRr += meanRr * meanRr
        sumHr += hr
        sumSqHr += hr * hr
        sumRmssd += rmssd
        sumSqRmssd += rmssd * rmssd
        sumSdnn += sdnn
        sumSqSdnn += sdnn * sdnn
        sumPnn50 += pnn50
        sumSqPnn50 += pnn50 * pnn50
        sumCv += cvRr
        sumSqCv += cvRr * cvRr

        return EpochHrvRaw(
            epochIndex = epochIdx,
            meanRr = meanRr,
            hr = hr,
            rmssd = rmssd,
            sdnn = sdnn,
            pnn50 = pnn50,
            cvRr = cvRr,
            hrSurge = hrSurge,
            autonomicBalance = autonomicBalance
        )
    }

    private fun calcZ(value: Double, sum: Double, sumSq: Double, count: Int): Double {
        if (count < 2) return 0.0
        val mean = sum / count
        val variance = max(1e-6, (sumSq / count) - mean * mean)
        return (value - mean) / sqrt(variance)
    }

    private fun extractFeatures(): DoubleArray {
        val list = buffer.toList()
        val target = list[TARGET_INDEX]

        // 1. Z-scores for target epoch
        val meanRrZ = calcZ(target.meanRr, sumMeanRr, sumSqMeanRr, statCount)
        val hrZ = calcZ(target.hr, sumHr, sumSqHr, statCount)
        val rmssdZ = calcZ(target.rmssd, sumRmssd, sumSqRmssd, statCount)
        val sdnnZ = calcZ(target.sdnn, sumSdnn, sumSqSdnn, statCount)
        val pnn50Z = calcZ(target.pnn50, sumPnn50, sumSqPnn50, statCount)
        val cvRrZ = calcZ(target.cvRr, sumCv, sumSqCv, statCount)
        val hrSurgeZ = target.hrSurge
        val autonomicBalanceZ = target.autonomicBalance

        // 2. Rolling past 5m (indices 0..9)
        var hrZPastSum = 0.0
        var rmssdZPastSum = 0.0
        var cvZPastSum = 0.0
        var autoZPastSum = 0.0
        for (i in 0 until TARGET_INDEX) {
            hrZPastSum += calcZ(list[i].hr, sumHr, sumSqHr, statCount)
            rmssdZPastSum += calcZ(list[i].rmssd, sumRmssd, sumSqRmssd, statCount)
            cvZPastSum += calcZ(list[i].cvRr, sumCv, sumSqCv, statCount)
            autoZPastSum += list[i].autonomicBalance
        }
        val hrZPast5m = hrZPastSum / TARGET_INDEX
        val rmssdZPast5m = rmssdZPastSum / TARGET_INDEX
        val cvZPast5m = cvZPastSum / TARGET_INDEX
        val autoZPast5m = autoZPastSum / TARGET_INDEX

        // 3. Rolling future 5m (indices 11..20)
        var hrZFutureSum = 0.0
        var rmssdZFutureSum = 0.0
        var cvZFutureSum = 0.0
        var autoZFutureSum = 0.0
        for (i in (TARGET_INDEX + 1) until WINDOW_SIZE) {
            hrZFutureSum += calcZ(list[i].hr, sumHr, sumSqHr, statCount)
            rmssdZFutureSum += calcZ(list[i].rmssd, sumRmssd, sumSqRmssd, statCount)
            cvZFutureSum += calcZ(list[i].cvRr, sumCv, sumSqCv, statCount)
            autoZFutureSum += list[i].autonomicBalance
        }
        val hrZFuture5m = hrZFutureSum / (WINDOW_SIZE - TARGET_INDEX - 1)
        val rmssdZFuture5m = rmssdZFutureSum / (WINDOW_SIZE - TARGET_INDEX - 1)
        val cvZFuture5m = cvZFutureSum / (WINDOW_SIZE - TARGET_INDEX - 1)
        val autoZFuture5m = autoZFutureSum / (WINDOW_SIZE - TARGET_INDEX - 1)

        return doubleArrayOf(
            meanRrZ,
            hrZ,
            rmssdZ,
            sdnnZ,
            pnn50Z,
            cvRrZ,
            hrSurgeZ,
            autonomicBalanceZ,
            hrZPast5m,
            hrZFuture5m,
            rmssdZPast5m,
            rmssdZFuture5m,
            cvZPast5m,
            cvZFuture5m,
            autoZPast5m,
            autoZFuture5m
        )
    }

    @Synchronized
    fun reset() {
        currentEpochRrs.clear()
        epochStartTimeMs = 0L
        currentEpochIndex = 0L
        buffer.clear()
        statCount = 0
        sumMeanRr = 0.0
        sumSqMeanRr = 0.0
        sumHr = 0.0
        sumSqHr = 0.0
        sumRmssd = 0.0
        sumSqRmssd = 0.0
        sumSdnn = 0.0
        sumSqSdnn = 0.0
        sumPnn50 = 0.0
        sumSqPnn50 = 0.0
        sumCv = 0.0
        sumSqCv = 0.0
        minHrTracked = 200.0
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description="Train True-HRV REM LightGBM Classifier on PhysioNet slpdb")
    parser.add_argument("--cache-dir", default="slpdb_cache", help="Path to local slpdb cached annotations")
    parser.add_argument("--output-dir", default="../app/src/main/java/com/flashalarm/miband/domain/algorithm",
                        help="Path to export Java and Kotlin files")
    parser.add_argument("--include-wake", action="store_true", help="Include wake epochs (default: false, sleep only)")
    args = parser.parse_args()

    print("==================================================================")
    print("  FlashAlarm True-HRV (AD8232/ESP32-C3) REM Sleep Model Trainer   ")
    print("==================================================================")

    # 1. Ensure dataset exists
    ensure_dataset_cached(args.cache_dir)

    # 2. Extract epochs
    print("[+] Extracting 30s epochs and computing R-R intervals from ECG...")
    df_raw = extract_epochs(args.cache_dir)
    print(f"[+] Total raw epochs extracted: {len(df_raw)}")

    # 3. Filter sleep vs wake
    if not args.include_wake:
        print("[+] Filtering to SLEEP epochs only (excluding active Wake, as handled by Actigraphy/Movement Veto)...")
        df_sleep = df_raw[df_raw['stage'] != 'W'].copy()
    else:
        df_sleep = df_raw.copy()

    rem_count = int(df_sleep['is_rem'].sum())
    total_count = len(df_sleep)
    print(f"[+] Dataset after filtering: Total={total_count}, REM={rem_count} ({rem_count/total_count*100:.1f}%), Non-REM={total_count - rem_count}")

    # 4. Feature engineering
    print("[+] Engineering temporal context and baseline-invariant Z-score features...")
    df_feat = build_features(df_sleep)
    print(f"[+] Feature matrix prepared with {len(FEATURE_COLUMNS)} dimensions.")

    X = df_feat[FEATURE_COLUMNS].values
    y = df_feat['is_rem'].values
    groups = df_feat['subject_id'].values

    # 5. 5-Fold Group Cross Validation (No Subject Leakage)
    n_splits = min(5, df_feat['subject_id'].nunique())
    print(f"\n[+] Running {n_splits}-Fold Group Cross-Validation (split strictly by Subject ID)...")

    gkf = GroupKFold(n_splits=n_splits)
    oof_probs = np.zeros(len(y))

    for fold, (train_idx, val_idx) in enumerate(gkf.split(X, y, groups), 1):
        clf = lgb.LGBMClassifier(
            n_estimators=35,
            max_depth=4,
            num_leaves=15,
            min_child_samples=20,
            learning_rate=0.06,
            class_weight='balanced',
            random_state=42 + fold,
            verbose=-1
        )
        clf.fit(X[train_idx], y[train_idx])
        oof_probs[val_idx] = clf.predict_proba(X[val_idx])[:, 1]
        val_auc = roc_auc_score(y[val_idx], oof_probs[val_idx]) if len(np.unique(y[val_idx])) > 1 else 0.5
        print(f"  Fold {fold}: Validation ROC-AUC = {val_auc:.4f}")

    overall_auc = roc_auc_score(y, oof_probs)
    print("\n" + "=" * 50)
    print("  Out-of-Fold Cross-Validation Performance (No Subject Leakage)")
    print("=" * 50)
    print(f"Overall ROC-AUC Score: {overall_auc:.4f}")

    for th in [0.40, 0.50, 0.60, 0.65]:
        preds = (oof_probs >= th).astype(int)
        rep = classification_report(y, preds, target_names=['Non-REM', 'REM'], output_dict=True)
        print(f"Threshold {th:.2f} -> REM Precision: {rep['REM']['precision']*100:5.1f}%, Recall: {rep['REM']['recall']*100:5.1f}%, F1: {rep['REM']['f1-score']:.3f}")

    # 6. Fit Final Model on All Data & Export
    print(f"\n[+] Fitting final model on all {len(X)} epochs...")
    final_model = lgb.LGBMClassifier(
        n_estimators=35,
        max_depth=4,
        num_leaves=15,
        min_child_samples=20,
        learning_rate=0.06,
        class_weight='balanced',
        random_state=42,
        verbose=-1
    )
    final_model.fit(X, y)

    print("\nTop Feature Importances:")
    for name, imp in sorted(zip(FEATURE_COLUMNS, final_model.feature_importances_), key=lambda x: x[1], reverse=True)[:10]:
        print(f"  {name:25s}: {imp}")

    # Export to Java
    os.makedirs(args.output_dir, exist_ok=True)
    java_code = m2c.export_to_java(
        final_model,
        package_name="com.flashalarm.miband.domain.algorithm",
        class_name="RemHrvClassifierModel"
    )

    java_path = os.path.join(args.output_dir, "RemHrvClassifierModel.java")
    with open(java_path, "w", encoding="utf-8") as f:
        f.write(java_code)
    print(f"\n[+] Successfully exported Java model: {java_path} ({len(java_code)} bytes)")

    # Export Kotlin feature extractor
    kt_code = generate_kotlin_extractor()
    kt_path = os.path.join(args.output_dir, "RemHrvFeatureExtractor.kt")
    with open(kt_path, "w", encoding="utf-8") as f:
        f.write(kt_code)
    print(f"[+] Successfully exported Kotlin extractor: {kt_path}")

    # Also save local copies in rem_ml_trainer
    with open("RemHrvClassifierModel.java", "w", encoding="utf-8") as f:
        f.write(java_code)
    with open("RemHrvFeatureExtractor.kt", "w", encoding="utf-8") as f:
        f.write(kt_code)

    print("\n[SUCCESS] True-HRV Pipeline Ready for Android & ESP32-C3!")


if __name__ == "__main__":
    main()

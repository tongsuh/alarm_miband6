#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FlashAlarm - PAAWS R2 True-HRV & Actigraphy REM Sleep Model Trainer
===================================================================
Streams, extracts, aligns, and trains a state-of-the-art LightGBM REM
sleep staging classifier directly from the official PAAWS R2 dataset
(141 subjects, 253 nights, ~1900 hours).

Modalities extracted:
  - 200 Hz Lead II EKG -> QRS Pan-Tompkins -> True ms-level R-R intervals
  - 20 Hz Triaxial Accelerometer (X, Y, Z in units of g) -> Motion metrics
  - 30-second AASM clinical PSG Ground Truth (Wake, N1, N2, N3, REM)

Output:
  - RemHrvClassifierModel.java (Pure Java, <64KB JVM bytecode)
  - RemHrvFeatureExtractor.kt (Android Kotlin feature extractor)
"""

import os
import sys
import glob
import time
import argparse
import datetime
import numpy as np
import pandas as pd
import scipy.signal as signal
from sklearn.model_selection import GroupKFold
from sklearn.metrics import classification_report, roc_auc_score, confusion_matrix
import concurrent.futures
import lightgbm as lgb
import m2cgen as m2c


# ----------------------------------------------------------------------
# 1. High-Performance Low-Memory EDF Header & Chunk Reader
# ----------------------------------------------------------------------

class EdfChunkReader:
    """Lightweight pure Python/NumPy EDF parser with zero heavy dependencies."""
    def __init__(self, edf_path: str):
        self.edf_path = edf_path
        with open(edf_path, 'rb') as f:
            h = f.read(256)
            self.start_date = h[168:176].decode('ascii', errors='ignore').strip()
            self.start_time = h[176:184].decode('ascii', errors='ignore').strip()
            self.header_bytes = int(h[184:192].decode('ascii', errors='ignore').strip())
            self.n_records = int(h[236:244].decode('ascii', errors='ignore').strip())
            self.duration = float(h[244:252].decode('ascii', errors='ignore').strip())
            self.ns = int(h[252:256].decode('ascii', errors='ignore').strip())

            self.labels = [f.read(16).decode('ascii', errors='ignore').strip() for _ in range(self.ns)]
            f.seek(256 + self.ns * 16 + self.ns * 80 + self.ns * 8)
            self.phys_min = [float(f.read(8).decode('ascii', errors='ignore').strip()) for _ in range(self.ns)]
            self.phys_max = [float(f.read(8).decode('ascii', errors='ignore').strip()) for _ in range(self.ns)]
            self.dig_min = [float(f.read(8).decode('ascii', errors='ignore').strip()) for _ in range(self.ns)]
            self.dig_max = [float(f.read(8).decode('ascii', errors='ignore').strip()) for _ in range(self.ns)]
            f.seek(256 + self.ns * 16 + self.ns * 80 + self.ns * 8 + self.ns * 8 * 4 + self.ns * 80)
            self.samples_per_rec = [int(f.read(8).decode('ascii', errors='ignore').strip()) for _ in range(self.ns)]

        self.bytes_per_record = sum(self.samples_per_rec) * 2
        self.offsets = [0]
        for s in self.samples_per_rec[:-1]:
            self.offsets.append(self.offsets[-1] + s * 2)

        # Parse absolute start time
        try:
            d, m, y = map(int, self.start_date.split('.'))
            year = 2000 + y if y < 50 else 1900 + y
            H, M, S = map(int, self.start_time.split('.'))
            self.start_datetime = datetime.datetime(year, m, d, H, M, S)
        except Exception:
            self.start_datetime = None

        # Find target signals
        self.ekg_idx = None
        for name in ['EKG', 'ECG']:
            if name in self.labels:
                self.ekg_idx = self.labels.index(name)
                break

        self.x_idx = self.labels.index('X Axis') if 'X Axis' in self.labels else None
        self.y_idx = self.labels.index('Y Axis') if 'Y Axis' in self.labels else None
        self.z_idx = self.labels.index('Z Axis') if 'Z Axis' in self.labels else None

    def read_epoch_file(self, f, start_sec: int, duration_sec: int = 30):
        """Extracts EKG and 3-axis accel slices using an already opened file handle f."""
        if start_sec < 0 or (start_sec + duration_sec) > self.n_records:
            return None, None

        ekg_chunks = []
        x_chunks, y_chunks, z_chunks = [], [], []

        ekg_samps = self.samples_per_rec[self.ekg_idx] if self.ekg_idx is not None else 0
        acc_samps = self.samples_per_rec[self.x_idx] if self.x_idx is not None else 0

        for s in range(start_sec, start_sec + duration_sec):
            rec_offset = self.header_bytes + s * self.bytes_per_record

            # Read EKG
            if self.ekg_idx is not None:
                f.seek(rec_offset + self.offsets[self.ekg_idx])
                raw_ekg = np.fromfile(f, dtype='<i2', count=ekg_samps)
                scale = (self.phys_max[self.ekg_idx] - self.phys_min[self.ekg_idx]) / max(1.0, (self.dig_max[self.ekg_idx] - self.dig_min[self.ekg_idx]))
                ekg_chunks.append((raw_ekg - self.dig_min[self.ekg_idx]) * scale + self.phys_min[self.ekg_idx])

            # Read Accel
            if self.x_idx is not None and self.y_idx is not None and self.z_idx is not None:
                f.seek(rec_offset + self.offsets[self.x_idx])
                x_chunks.append(np.fromfile(f, dtype='<i2', count=acc_samps))
                f.seek(rec_offset + self.offsets[self.y_idx])
                y_chunks.append(np.fromfile(f, dtype='<i2', count=acc_samps))
                f.seek(rec_offset + self.offsets[self.z_idx])
                z_chunks.append(np.fromfile(f, dtype='<i2', count=acc_samps))

        ekg_data = np.concatenate(ekg_chunks) if ekg_chunks else None

        acc_data = None
        if x_chunks and y_chunks and z_chunks:
            scale_acc = (self.phys_max[self.x_idx] - self.phys_min[self.x_idx]) / max(1.0, (self.dig_max[self.x_idx] - self.dig_min[self.x_idx]))
            x = (np.concatenate(x_chunks) - self.dig_min[self.x_idx]) * scale_acc + self.phys_min[self.x_idx]
            y = (np.concatenate(y_chunks) - self.dig_min[self.y_idx]) * scale_acc + self.phys_min[self.y_idx]
            z = (np.concatenate(z_chunks) - self.dig_min[self.z_idx]) * scale_acc + self.phys_min[self.z_idx]
            acc_data = (x, y, z)

        return ekg_data, acc_data

    def read_epoch(self, start_sec: int, duration_sec: int = 30):
        """Extracts EKG and 3-axis accel slices for a 30s epoch starting at second start_sec."""
        with open(self.edf_path, 'rb') as f:
            return self.read_epoch_file(f, start_sec, duration_sec)


# ----------------------------------------------------------------------
# 2. QRS Peak Detection & HRV Signal Processing
# ----------------------------------------------------------------------

# Pre-computed Butterworth bandpass filter coefficients for 200 Hz sampling (5-15 Hz)
_BUTTER_B, _BUTTER_A = signal.butter(2, [5.0 / 100.0, 15.0 / 100.0], btype='bandpass')

def detect_qrs_peaks_200hz(ekg_200hz: np.ndarray) -> np.ndarray:
    """Pan-Tompkins QRS detector optimized for 200 Hz Lead II ECG."""
    if len(ekg_200hz) < 200:
        return np.array([])

    # 1. 5-15 Hz bandpass
    filtered = signal.filtfilt(_BUTTER_B, _BUTTER_A, ekg_200hz)

    # 2. Five-point derivative: y[n] = (2x[n] + x[n-1] - x[n-3] - 2x[n-4]) / 8
    diff = np.diff(filtered, prepend=filtered[0])

    # 3. Squaring
    squared = diff ** 2

    # 4. Moving window integration (150 ms = 30 samples @ 200 Hz)
    window_len = 30
    integrated = np.convolve(squared, np.ones(window_len) / window_len, mode='same')

    # 5. Peak picking with 250ms refractory blanking (50 samples)
    min_distance = 50
    threshold = np.mean(integrated) + 0.45 * np.std(integrated)
    peaks, _ = signal.find_peaks(integrated, height=threshold, distance=min_distance)

    # Refine peak to local extreme in raw EKG within +/- 40ms (+/- 8 samples)
    refined_peaks = []
    n = len(ekg_200hz)
    for p in peaks:
        lo = max(0, p - 8)
        hi = min(n, p + 8)
        refined = lo + np.argmax(np.abs(ekg_200hz[lo:hi]))
        refined_peaks.append(refined)

    return np.array(refined_peaks)


def compute_epoch_metrics(ekg_data: np.ndarray, acc_data: tuple):
    """Computes HRV and Actigraphy metrics for a single 30s epoch."""
    # --- HRV Features ---
    if ekg_data is None or len(ekg_data) < 200:
        return None

    peaks = detect_qrs_peaks_200hz(ekg_data)
    if len(peaks) < 8:
        return None

    rrs = np.diff(peaks) / 200.0 * 1000.0
    rrs_clean = rrs[(rrs >= 300.0) & (rrs <= 2000.0)]
    if len(rrs_clean) < 6:
        return None

    mean_rr = float(np.mean(rrs_clean))
    hr = float(60000.0 / mean_rr) if mean_rr > 0 else 0.0
    sdnn = float(np.std(rrs_clean))

    diffs = np.diff(rrs_clean)
    rmssd = float(np.sqrt(np.mean(diffs ** 2))) if len(diffs) > 0 else 0.0
    pnn50 = float(np.mean(np.abs(diffs) > 50.0) * 100.0) if len(diffs) > 0 else 0.0
    cv_rr = float(sdnn / mean_rr) if mean_rr > 0 else 0.0
    autonomic_balance = float(sdnn / max(1.0, rmssd))

    # --- Actigraphy Features ---
    if acc_data is not None:
        x, y, z = acc_data
        vm = np.sqrt(x**2 + y**2 + z**2)
        # Dynamic acceleration deviation from gravity vector
        motion_dev = np.abs(vm - np.mean(vm))
        motion_mean = float(np.mean(motion_dev))
        motion_max = float(np.max(motion_dev))
        motion_std = float(np.std(motion_dev))
    else:
        motion_mean, motion_max, motion_std = 0.0, 0.0, 0.0

    return {
        'mean_rr': mean_rr,
        'hr': hr,
        'rmssd': rmssd,
        'sdnn': sdnn,
        'pnn50': pnn50,
        'cv_rr': cv_rr,
        'autonomic_balance': autonomic_balance,
        'motion_mean': motion_mean,
        'motion_max': motion_max,
        'motion_std': motion_std
    }


# ----------------------------------------------------------------------
# 3. Full Dataset Streaming Extraction & Alignment
# ----------------------------------------------------------------------

def process_single_night(task_tuple):
    """Processes a single subject recording night with persistent file handle."""
    sub_id, csv_path, edf_path = task_tuple
    try:
        reader = EdfChunkReader(edf_path)
        if reader.start_datetime is None or reader.ekg_idx is None:
            return []

        df_events = pd.read_csv(csv_path)
        stages_df = df_events[df_events['Event'].isin(['Wake', 'N1', 'N2', 'N3', 'REM'])].copy()
        if stages_df.empty:
            return []

        stages_df['dt_start'] = pd.to_datetime(stages_df['Start Time'])
        stages_df['offset_sec'] = (stages_df['dt_start'] - reader.start_datetime).dt.total_seconds()

        # Filter out obvious time stamp mismatches
        stages_df = stages_df[(stages_df['offset_sec'] >= 0) & (stages_df['offset_sec'] + 30 <= reader.n_records)]
        if stages_df.empty:
            return []

        base_name = os.path.basename(csv_path).replace("_scored_events.csv", "")
        epochs = []

        with open(edf_path, 'rb') as f:
            night_epoch_idx = 0
            for _, row in stages_df.iterrows():
                sec_start = int(round(row['offset_sec']))
                stage = row['Event']

                ekg_chunk, acc_chunk = reader.read_epoch_file(f, sec_start, 30)
                metrics = compute_epoch_metrics(ekg_chunk, acc_chunk)
                if metrics is None:
                    continue

                metrics['subject_id'] = sub_id
                metrics['recording_id'] = base_name
                metrics['night_epoch'] = night_epoch_idx
                metrics['stage'] = stage
                metrics['is_rem'] = 1 if stage == 'REM' else 0

                epochs.append(metrics)
                night_epoch_idx += 1

        return epochs
    except Exception as e:
        return []


def extract_paaws_dataset(data_dir: str, cache_file: str, sample_limit: int = 0) -> pd.DataFrame:
    """Iterates through all PAAWS_Sleep subjects and extracts aligned 30s epoch features in parallel."""
    if os.path.exists(cache_file):
        print(f"[+] Found cached feature file: {cache_file}")
        print(f"[+] Loading pre-extracted features directly (instant load)...")
        return pd.read_csv(cache_file)

    sleep_dir = os.path.join(data_dir, "PAAWS_Sleep") if os.path.exists(os.path.join(data_dir, "PAAWS_Sleep")) else data_dir
    subject_dirs = sorted([d for d in os.listdir(sleep_dir) if os.path.isdir(os.path.join(sleep_dir, d)) and d.startswith("DS_")])

    if sample_limit > 0:
        subject_dirs = subject_dirs[:sample_limit]

    tasks = []
    for sub_id in subject_dirs:
        sub_path = os.path.join(sleep_dir, sub_id)
        csv_files = sorted(glob.glob(os.path.join(sub_path, "*_scored_events.csv")))
        for csv_path in csv_files:
            base_name = os.path.basename(csv_path).replace("_scored_events.csv", "")
            edf_path = os.path.join(sub_path, base_name + ".edf")
            if os.path.exists(edf_path):
                tasks.append((sub_id, csv_path, edf_path))

    print(f"[+] Discovered {len(subject_dirs)} subjects ({len(tasks)} recording nights) to process...")
    t0 = time.time()
    all_epochs = []

    workers = min(8, os.cpu_count() or 4)
    print(f"[+] Spawning {workers} parallel worker processes for streaming feature extraction...")

    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as executor:
        future_to_task = {executor.submit(process_single_night, t): t for t in tasks}
        completed = 0
        for future in concurrent.futures.as_completed(future_to_task):
            res = future.result()
            all_epochs.extend(res)
            completed += 1
            if completed % 10 == 0 or completed == len(tasks):
                elapsed = time.time() - t0
                print(f"  Processed {completed}/{len(tasks)} nights | Extracted Epochs: {len(all_epochs)} | Elapsed: {elapsed:.1f}s", flush=True)

    print(f"[+] Total extraction finished: {len(all_epochs)} valid 30s epochs from {len(tasks)} nights in {time.time()-t0:.1f}s.")
    df_result = pd.DataFrame(all_epochs)

    # Save cache
    os.makedirs(os.path.dirname(os.path.abspath(cache_file)), exist_ok=True)
    df_result.to_csv(cache_file, index=False)
    print(f"[+] Saved structured feature cache to: {cache_file} ({os.path.getsize(cache_file)/1024/1024:.2f} MB)")
    return df_result


# ----------------------------------------------------------------------
# 4. Temporal Rolling Context & Subject Adaptive Normalization
# ----------------------------------------------------------------------

FEATURE_NAMES = [
    'mean_rr_z',
    'hr_z',
    'rmssd_z',
    'sdnn_z',
    'pnn50_z',
    'cv_rr_z',
    'hr_surge_z',
    'autonomic_balance_z',
    'motion_mean_z',
    'motion_max_z',
    'hr_z_past_5m',
    'hr_z_future_5m',
    'rmssd_z_past_5m',
    'rmssd_z_future_5m',
    'cv_rr_z_past_5m',
    'cv_rr_z_future_5m',
    'motion_past_5m',
    'motion_future_5m'
]

def engineer_features(df: pd.DataFrame, include_wake: bool = False) -> pd.DataFrame:
    """Computes rolling temporal context and subject-invariant Z-scores."""
    if not include_wake:
        print("[+] Filtering out active Wake epochs (handled in Android by Movement Veto & Sleep Onset Protection)...")
        df_sleep = df[df['stage'] != 'Wake'].copy()
    else:
        df_sleep = df.copy()

    feature_dfs = []
    for rec_id, rec_df in df_sleep.groupby('recording_id'):
        rec_df = rec_df.sort_values('night_epoch').copy()
        if len(rec_df) < 25:
            continue

        # Baseline HR tracking (10th percentile over rolling 30 mins = 60 epochs)
        base_hr = rec_df['hr'].rolling(60, min_periods=10).quantile(0.10).fillna(rec_df['hr'].min())
        rec_df['hr_surge'] = (rec_df['hr'] - base_hr) / base_hr.clip(lower=40.0)

        # Subject night-level Z-score normalization
        norm_cols = ['mean_rr', 'hr', 'rmssd', 'sdnn', 'pnn50', 'cv_rr', 'hr_surge', 'autonomic_balance', 'motion_mean', 'motion_max']
        for col in norm_cols:
            m = rec_df[col].mean()
            s = rec_df[col].std() + 1e-6
            rec_df[col + '_z'] = (rec_df[col] - m) / s

        # Rolling 5-minute past (10 epochs)
        rec_df['hr_z_past_5m'] = rec_df['hr_z'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['hr_z'])
        rec_df['rmssd_z_past_5m'] = rec_df['rmssd_z'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['rmssd_z'])
        rec_df['cv_rr_z_past_5m'] = rec_df['cv_rr_z'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['cv_rr_z'])
        rec_df['motion_past_5m'] = rec_df['motion_mean_z'].shift(1).rolling(10, min_periods=3).mean().fillna(rec_df['motion_mean_z'])

        # Rolling 5-minute future (10 epochs, within 5-min latency allowance)
        rec_df['hr_z_future_5m'] = rec_df['hr_z'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1].fillna(rec_df['hr_z'])
        rec_df['rmssd_z_future_5m'] = rec_df['rmssd_z'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1].fillna(rec_df['rmssd_z'])
        rec_df['cv_rr_z_future_5m'] = rec_df['cv_rr_z'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1].fillna(rec_df['cv_rr_z'])
        rec_df['motion_future_5m'] = rec_df['motion_mean_z'].iloc[::-1].shift(1).rolling(10, min_periods=3).mean().iloc[::-1].fillna(rec_df['motion_mean_z'])

        feature_dfs.append(rec_df)

    df_out = pd.concat(feature_dfs, ignore_index=True).dropna(subset=FEATURE_NAMES)
    return df_out


# ----------------------------------------------------------------------
# 5. Model Training, Group Cross-Validation & Code Generation
# ----------------------------------------------------------------------

def generate_kotlin_extractor() -> str:
    """Generates the Android Kotlin feature extractor supporting HRV + Motion dual-modality."""
    return """package com.flashalarm.miband.domain.algorithm

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time True-HRV & Actigraphy Dual-Modality Feature Extractor for FlashAlarm.
 * Trained on PAAWS R2 (141 Subjects, 253 Nights).
 *
 * Buffers:
 *   - Millisecond R-R intervals from AD8232 / ESP32-C3
 *   - 3-axis Actigraphy vector magnitude from Xiaomi Mi Band 6
 *   - Maintains rolling 21-epoch buffer (5m past + current + 5m future)
 */
class RemHrvFeatureExtractor {

    companion object {
        const val EPOCH_DURATION_MS = 30_000L
        const val WINDOW_SIZE = 21       // 10 past + 1 current + 10 future epochs (~10.5 minutes)
        const val TARGET_INDEX = 10      // Center epoch (5-min latency allowance)
    }

    data class EpochRawData(
        val epochIndex: Long,
        val meanRr: Double,
        val hr: Double,
        val rmssd: Double,
        val sdnn: Double,
        val pnn50: Double,
        val cvRr: Double,
        val hrSurge: Double,
        val autonomicBalance: Double,
        val motionMean: Double,
        val motionMax: Double
    )

    private val currentEpochRrs = ArrayList<Double>()
    private val currentEpochMotions = ArrayList<Double>()
    private var epochStartTimeMs: Long = 0L
    private var currentEpochIndex: Long = 0L

    // Online running statistics for Z-score normalization
    private var statCount = 0
    private var sumMeanRr = 0.0; private var sumSqMeanRr = 0.0
    private var sumHr = 0.0;     private var sumSqHr = 0.0
    private var sumRmssd = 0.0;  private var sumSqRmssd = 0.0
    private var sumSdnn = 0.0;   private var sumSqSdnn = 0.0
    private var sumPnn50 = 0.0;  private var sumSqPnn50 = 0.0
    private var sumCv = 0.0;     private var sumSqCv = 0.0
    private var sumAuto = 0.0;   private var sumSqAuto = 0.0
    private var sumMotM = 0.0;   private var sumSqMotM = 0.0
    private var sumMotX = 0.0;   private var sumSqMotX = 0.0
    private var minHrTracked = 200.0

    private val buffer = ArrayDeque<EpochRawData>()

    /**
     * Push incoming millisecond R-R interval from ESP32-C3 / BLE Heart Rate Service.
     */
    @Synchronized
    fun pushRrInterval(rrMs: Double, timestampMs: Long) {
        if (rrMs in 300.0..2000.0) {
            currentEpochRrs.add(rrMs)
        }
        checkEpochComplete(timestampMs)
    }

    /**
     * Push incoming Actigraphy motion magnitude from Mi Band 6.
     */
    @Synchronized
    fun pushMotion(motionG: Double, timestampMs: Long) {
        currentEpochMotions.add(motionG)
        checkEpochComplete(timestampMs)
    }

    /**
     * Direct push at the end of an epoch (if synchronized externally).
     * @return 18-element feature array for RemHrvClassifierModel.score(), or null if buffering
     */
    @Synchronized
    fun pushEpoch(
        epochIdx: Long,
        meanRr: Double,
        hr: Double,
        rmssd: Double,
        sdnn: Double,
        pnn50: Double,
        cvRr: Double,
        motionMean: Double,
        motionMax: Double
    ): DoubleArray? {
        minHrTracked = min(minHrTracked, hr)
        val hrSurge = (hr - minHrTracked) / max(40.0, minHrTracked)
        val autonomicBalance = sdnn / max(1.0, rmssd)

        updateStats(meanRr, hr, rmssd, sdnn, pnn50, cvRr, autonomicBalance, motionMean, motionMax)

        val epoch = EpochRawData(
            epochIndex = epochIdx,
            meanRr = meanRr,
            hr = hr,
            rmssd = rmssd,
            sdnn = sdnn,
            pnn50 = pnn50,
            cvRr = cvRr,
            hrSurge = hrSurge,
            autonomicBalance = autonomicBalance,
            motionMean = motionMean,
            motionMax = motionMax
        )

        buffer.addLast(epoch)
        if (buffer.size > WINDOW_SIZE) buffer.removeFirst()

        return if (buffer.size == WINDOW_SIZE) extractFeatures() else null
    }

    private fun checkEpochComplete(timestampMs: Long): DoubleArray? {
        if (epochStartTimeMs == 0L) epochStartTimeMs = timestampMs

        if (timestampMs - epochStartTimeMs >= EPOCH_DURATION_MS) {
            val epoch = completeEpoch(currentEpochIndex++)
            currentEpochRrs.clear()
            currentEpochMotions.clear()
            epochStartTimeMs = timestampMs

            if (epoch != null) {
                buffer.addLast(epoch)
                if (buffer.size > WINDOW_SIZE) buffer.removeFirst()
                if (buffer.size == WINDOW_SIZE) return extractFeatures()
            }
        }
        return null
    }

    private fun completeEpoch(epochIdx: Long): EpochRawData? {
        if (currentEpochRrs.size < 5) return null

        val n = currentEpochRrs.size
        val meanRr = currentEpochRrs.average()
        val hr = 60000.0 / meanRr

        var varSum = 0.0
        for (rr in currentEpochRrs) varSum += (rr - meanRr).pow(2)
        val sdnn = sqrt(varSum / n)

        var diffSqSum = 0.0
        var count50 = 0
        for (i in 0 until n - 1) {
            val diff = currentEpochRrs[i + 1] - currentEpochRrs[i]
            diffSqSum += diff * diff
            if (kotlin.math.abs(diff) > 50.0) count50++
        }
        val rmssd = if (n > 1) sqrt(diffSqSum / (n - 1)) else 0.0
        val pnn50 = if (n > 1) (count50.toDouble() / (n - 1)) * 100.0 else 0.0
        val cvRr = if (meanRr > 0) sdnn / meanRr else 0.0

        minHrTracked = min(minHrTracked, hr)
        val hrSurge = (hr - minHrTracked) / max(40.0, minHrTracked)
        val autonomicBalance = sdnn / max(1.0, rmssd)

        val motionMean = if (currentEpochMotions.isNotEmpty()) currentEpochMotions.average() else 0.0
        val motionMax = if (currentEpochMotions.isNotEmpty()) currentEpochMotions.maxOrNull() ?: 0.0 else 0.0

        updateStats(meanRr, hr, rmssd, sdnn, pnn50, cvRr, autonomicBalance, motionMean, motionMax)

        return EpochRawData(
            epochIndex = epochIdx,
            meanRr = meanRr,
            hr = hr,
            rmssd = rmssd,
            sdnn = sdnn,
            pnn50 = pnn50,
            cvRr = cvRr,
            hrSurge = hrSurge,
            autonomicBalance = autonomicBalance,
            motionMean = motionMean,
            motionMax = motionMax
        )
    }

    private fun updateStats(meanRr: Double, hr: Double, rmssd: Double, sdnn: Double, pnn50: Double,
                            cvRr: Double, auto: Double, motM: Double, motX: Double) {
        statCount++
        sumMeanRr += meanRr; sumSqMeanRr += meanRr * meanRr
        sumHr += hr;         sumSqHr += hr * hr
        sumRmssd += rmssd;   sumSqRmssd += rmssd * rmssd
        sumSdnn += sdnn;     sumSqSdnn += sdnn * sdnn
        sumPnn50 += pnn50;   sumSqPnn50 += pnn50 * pnn50
        sumCv += cvRr;       sumSqCv += cvRr * cvRr
        sumAuto += auto;     sumSqAuto += auto * auto
        sumMotM += motM;     sumSqMotM += motM * motM
        sumMotX += motX;     sumSqMotX += motX * motX
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

        val meanRrZ = calcZ(target.meanRr, sumMeanRr, sumSqMeanRr, statCount)
        val hrZ = calcZ(target.hr, sumHr, sumSqHr, statCount)
        val rmssdZ = calcZ(target.rmssd, sumRmssd, sumSqRmssd, statCount)
        val sdnnZ = calcZ(target.sdnn, sumSdnn, sumSqSdnn, statCount)
        val pnn50Z = calcZ(target.pnn50, sumPnn50, sumSqPnn50, statCount)
        val cvRrZ = calcZ(target.cvRr, sumCv, sumSqCv, statCount)
        val hrSurgeZ = target.hrSurge
        val autoZ = calcZ(target.autonomicBalance, sumAuto, sumSqAuto, statCount)
        val motMZ = calcZ(target.motionMean, sumMotM, sumSqMotM, statCount)
        val motXZ = calcZ(target.motionMax, sumMotX, sumSqMotX, statCount)

        var hrPastSum = 0.0; var rmssdPastSum = 0.0; var cvPastSum = 0.0; var motPastSum = 0.0
        for (i in 0 until TARGET_INDEX) {
            hrPastSum += calcZ(list[i].hr, sumHr, sumSqHr, statCount)
            rmssdPastSum += calcZ(list[i].rmssd, sumRmssd, sumSqRmssd, statCount)
            cvPastSum += calcZ(list[i].cvRr, sumCv, sumSqCv, statCount)
            motPastSum += calcZ(list[i].motionMean, sumMotM, sumSqMotM, statCount)
        }
        val hrZPast5m = hrPastSum / TARGET_INDEX
        val rmssdZPast5m = rmssdPastSum / TARGET_INDEX
        val cvZPast5m = cvPastSum / TARGET_INDEX
        val motPast5m = motPastSum / TARGET_INDEX

        var hrFutSum = 0.0; var rmssdFutSum = 0.0; var cvFutSum = 0.0; var motFutSum = 0.0
        val futCount = WINDOW_SIZE - TARGET_INDEX - 1
        for (i in (TARGET_INDEX + 1) until WINDOW_SIZE) {
            hrFutSum += calcZ(list[i].hr, sumHr, sumSqHr, statCount)
            rmssdFutSum += calcZ(list[i].rmssd, sumRmssd, sumSqRmssd, statCount)
            cvFutSum += calcZ(list[i].cvRr, sumCv, sumSqCv, statCount)
            motFutSum += calcZ(list[i].motionMean, sumMotM, sumSqMotM, statCount)
        }
        val hrZFut5m = hrFutSum / futCount
        val rmssdZFut5m = rmssdFutSum / futCount
        val cvZFut5m = cvFutSum / futCount
        val motFut5m = motFutSum / futCount

        return doubleArrayOf(
            meanRrZ, hrZ, rmssdZ, sdnnZ, pnn50Z, cvRrZ, hrSurgeZ, autoZ,
            motMZ, motXZ,
            hrZPast5m, hrZFut5m,
            rmssdZPast5m, rmssdZFut5m,
            cvZPast5m, cvZFut5m,
            motPast5m, motFut5m
        )
    }

    @Synchronized
    fun reset() {
        currentEpochRrs.clear()
        currentEpochMotions.clear()
        epochStartTimeMs = 0L
        currentEpochIndex = 0L
        buffer.clear()
        statCount = 0
        sumMeanRr = 0.0; sumSqMeanRr = 0.0
        sumHr = 0.0;     sumSqHr = 0.0
        sumRmssd = 0.0;  sumSqRmssd = 0.0
        sumSdnn = 0.0;   sumSqSdnn = 0.0
        sumPnn50 = 0.0;  sumSqPnn50 = 0.0
        sumCv = 0.0;     sumSqCv = 0.0
        sumAuto = 0.0;   sumSqAuto = 0.0
        sumMotM = 0.0;   sumSqMotM = 0.0
        sumMotX = 0.0;   sumSqMotX = 0.0
        minHrTracked = 200.0
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description="PAAWS R2 True-HRV & Actigraphy Dual-Modality REM Sleep Trainer")
    parser.add_argument("--data-dir", default=r"F:\paaws_r2_raw", help="Path to uncompressed PAAWS R2 directory")
    parser.add_argument("--cache-file", default="paaws_features_cache.csv", help="Path to cache extracted features")
    parser.add_argument("--output-dir", default="../app/src/main/java/com/flashalarm/miband/domain/algorithm",
                        help="Path to export Java and Kotlin files")
    parser.add_argument("--sample-limit", type=int, default=0, help="Limit number of subjects to process (0 = all)")
    parser.add_argument("--include-wake", action="store_true", help="Include wake epochs (default: False, sleep only)")
    args = parser.parse_args()

    print("==================================================================")
    print("  FlashAlarm - PAAWS R2 True-HRV + Actigraphy Sleep Model Trainer ")
    print("==================================================================")

    # 1. Extract or load cache
    df_raw = extract_paaws_dataset(args.data_dir, args.cache_file, args.sample_limit)
    print(f"[+] Total raw epochs: {len(df_raw)}")

    # 2. Engineer features
    print("[+] Engineering 18-dim True-HRV + Motion sliding context features...")
    df_feat = engineer_features(df_raw, args.include_wake)

    rem_count = int(df_feat['is_rem'].sum())
    total_count = len(df_feat)
    print(f"[+] Clean dataset for training: Total={total_count}, REM={rem_count} ({rem_count/total_count*100:.1f}%), Non-REM={total_count-rem_count}")
    print(f"[+] Unique subjects: {df_feat['subject_id'].nunique()}, Unique nights: {df_feat['recording_id'].nunique()}")

    X = df_feat[FEATURE_NAMES].values
    y = df_feat['is_rem'].values
    groups = df_feat['subject_id'].values

    # 3. 5-Fold Group Cross Validation (by Subject ID, zero leakage)
    n_splits = min(5, df_feat['subject_id'].nunique())
    print(f"\n[+] Running {n_splits}-Fold Group Cross-Validation (split strictly by Subject ID)...")

    gkf = GroupKFold(n_splits=n_splits)
    oof_probs = np.zeros(len(y))

    for fold, (train_idx, val_idx) in enumerate(gkf.split(X, y, groups), 1):
        # Calculate class balance weight
        n_pos = np.sum(y[train_idx] == 1)
        n_neg = np.sum(y[train_idx] == 0)
        scale_weight = float(n_neg) / max(1.0, float(n_pos))

        clf = lgb.LGBMClassifier(
            n_estimators=45,
            max_depth=4,
            num_leaves=15,
            min_child_samples=30,
            learning_rate=0.06,
            scale_pos_weight=scale_weight,
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

    for th in [0.40, 0.50, 0.55, 0.60, 0.65, 0.70]:
        preds = (oof_probs >= th).astype(int)
        rep = classification_report(y, preds, target_names=['Non-REM', 'REM'], output_dict=True)
        print(f"Threshold {th:.2f} -> REM Precision: {rep['REM']['precision']*100:5.1f}%, Recall: {rep['REM']['recall']*100:5.1f}%, F1: {rep['REM']['f1-score']:.3f}")

    # 4. Fit Final Model on All Data & Export
    print(f"\n[+] Fitting final model on all {len(X)} epochs...")
    n_pos_all = np.sum(y == 1)
    n_neg_all = np.sum(y == 0)
    final_model = lgb.LGBMClassifier(
        n_estimators=45,
        max_depth=4,
        num_leaves=15,
        min_child_samples=30,
        learning_rate=0.06,
        scale_pos_weight=float(n_neg_all) / max(1.0, float(n_pos_all)),
        random_state=42,
        verbose=-1
    )
    final_model.fit(X, y)

    print("\nTop Feature Importances:")
    for name, imp in sorted(zip(FEATURE_NAMES, final_model.feature_importances_), key=lambda x: x[1], reverse=True):
        print(f"  {name:25s}: {imp}")

    # 5. Export to Java
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

    # 6. Export Kotlin feature extractor
    kt_code = generate_kotlin_extractor()
    kt_path = os.path.join(args.output_dir, "RemHrvFeatureExtractor.kt")
    with open(kt_path, "w", encoding="utf-8") as f:
        f.write(kt_code)
    print(f"[+] Successfully exported Kotlin extractor: {kt_path}")

    # Local copies
    with open("RemHrvClassifierModel.java", "w", encoding="utf-8") as f:
        f.write(java_code)
    with open("RemHrvFeatureExtractor.kt", "w", encoding="utf-8") as f:
        f.write(kt_code)

    print("\n[SUCCESS] PAAWS R2 Pipeline Completed!")


if __name__ == "__main__":
    main()

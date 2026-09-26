package com.flashalarm.miband.ui.sleep

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flashalarm.miband.FlashAlarmApp
import com.flashalarm.miband.data.db.AlgorithmDiagnosticEntity
import com.flashalarm.miband.data.db.DreamCueEntity
import com.flashalarm.miband.data.db.SleepEpochEntity
import com.flashalarm.miband.data.db.SleepSessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FlashAlarmApp
    private val repository = app.sleepRepository

    val allSessions: StateFlow<List<SleepSessionEntity>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    private val _selectedSession = MutableStateFlow<SleepSessionEntity?>(null)
    val selectedSession: StateFlow<SleepSessionEntity?> = _selectedSession.asStateFlow()

    val currentEpochs: StateFlow<List<SleepEpochEntity>> = _selectedSessionId
        .flatMapLatest { id ->
            if (id != null) repository.getEpochsForSession(id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentCues: StateFlow<List<DreamCueEntity>> = _selectedSessionId
        .flatMapLatest { id ->
            if (id != null) repository.getCuesForSession(id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentDiagnostics: StateFlow<List<AlgorithmDiagnosticEntity>> = _selectedSessionId
        .flatMapLatest { id ->
            if (id != null) repository.getDiagnosticsForSession(id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var hasCheckedInitialSeed = false

    init {
        viewModelScope.launch {
            allSessions.collect { sessions ->
                if (sessions.isNotEmpty()) {
                    hasCheckedInitialSeed = true
                    if (_selectedSessionId.value == null || sessions.none { it.sessionId == _selectedSessionId.value }) {
                        selectSession(sessions.first().sessionId)
                    }
                } else {
                    if (!hasCheckedInitialSeed) {
                        hasCheckedInitialSeed = true
                        // Seed mock session on first startup so user can immediately experience the hypnogram chart
                        repository.seedMockSleepSession()
                    } else {
                        // User explicitly deleted all sessions: keep empty state
                        _selectedSessionId.value = null
                        _selectedSession.value = null
                    }
                }
            }
        }
    }

    fun selectSession(sessionId: Long) {
        _selectedSessionId.value = sessionId
        viewModelScope.launch {
            _selectedSession.value = repository.getSessionById(sessionId)
        }
    }

    fun generateMockSession() {
        viewModelScope.launch {
            val newId = repository.seedMockSleepSession()
            selectSession(newId)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            val currentList = allSessions.value.filter { it.sessionId != sessionId }
            if (currentList.isNotEmpty()) {
                selectSession(currentList.first().sessionId)
            } else {
                _selectedSessionId.value = null
                _selectedSession.value = null
            }
        }
    }
}

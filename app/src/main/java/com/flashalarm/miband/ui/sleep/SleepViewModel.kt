package com.flashalarm.miband.ui.sleep

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flashalarm.miband.FlashAlarmApp
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

    init {
        viewModelScope.launch {
            allSessions.collect { sessions ->
                if (sessions.isNotEmpty()) {
                    if (_selectedSessionId.value == null || sessions.none { it.sessionId == _selectedSessionId.value }) {
                        selectSession(sessions.first().sessionId)
                    }
                } else {
                    // Seed mock session on first startup so user can immediately experience the hypnogram chart
                    repository.seedMockSleepSession()
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
}

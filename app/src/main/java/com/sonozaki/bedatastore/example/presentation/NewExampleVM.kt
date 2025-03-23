package com.sonozaki.bedatastore.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonozaki.bedatastore.example.data.NewExampleData
import com.sonozaki.bedatastore.example.data.NewExampleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NewExampleVM @Inject constructor(private val exampleRepository: NewExampleRepository): ViewModel() {
    val data = exampleRepository.data.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(0,0),
        NewExampleData()
    )
}
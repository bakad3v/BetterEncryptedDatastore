package com.sonozaki.bedatastore.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonozaki.bedatastore.example.data.DBAExampleData
import com.sonozaki.bedatastore.example.data.ExampleData
import com.sonozaki.bedatastore.example.data.ExampleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExampleVM @Inject constructor(private val exampleRepository: ExampleRepository): ViewModel() {
    val data = exampleRepository.data.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(0,0),
        ExampleData()
    )

    val dbaData = exampleRepository.dbaData.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(0,0),
        DBAExampleData()
    )


    fun update(newData: ExampleData) {
        viewModelScope.launch {
            exampleRepository.update(newData)
        }
    }

    fun increaseDigit() {
        viewModelScope.launch {
            exampleRepository.increaseDigit()
        }
    }
}
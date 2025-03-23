package com.sonozaki.bedatastore.example.data

import kotlinx.serialization.Serializable

@Serializable
data class ExampleData(val text: String = "", val digit: Long = 0)
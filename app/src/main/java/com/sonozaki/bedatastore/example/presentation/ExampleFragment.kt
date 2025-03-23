package com.sonozaki.bedatastore.example.presentation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sonozaki.bedatastore.example.data.ExampleData
import com.sonozaki.bedatastore.example.data.ExampleRepository
import com.sonozaki.bedatastore.example.databinding.TestScreenBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@AndroidEntryPoint
class ExampleFragment: Fragment() {
    private var _binding: TestScreenBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExampleVM by viewModels()

    @Inject
    lateinit var repository: ExampleRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = TestScreenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.w("lifecycle","created")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.data.collect { exampleData ->
                    binding.encryptedObject.text = exampleData.toString()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dbaData.collect { exampleData ->
                  Log.w("dbaData",exampleData.text)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            delay(5000)
            val settings = repository.data.first()
            Log.w("settings", settings.toString())
        }


        binding.add.setOnClickListener {
            lifecycleScope.launch {
                viewModel.increaseDigit()
            }
        }


        fun generateRandomString(length: Int): String {
            val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..length)
                .map { charset[Random.nextInt(charset.length)] }
                .joinToString("")
        }

        binding.updateData.setOnClickListener {
            viewModel.update(ExampleData(generateRandomString(12), 1))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
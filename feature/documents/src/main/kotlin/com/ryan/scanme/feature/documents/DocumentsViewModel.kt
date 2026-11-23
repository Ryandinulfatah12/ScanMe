package com.ryan.scanme.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryan.scanme.core.domain.model.Document
import com.ryan.scanme.core.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val repository: DocumentRepository,
) : ViewModel() {

    val documents: StateFlow<List<Document>?> =
        repository.observeDocuments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun rename(id: Long, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch { repository.rename(id, t) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

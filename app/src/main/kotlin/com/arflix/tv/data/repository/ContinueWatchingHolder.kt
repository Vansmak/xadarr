package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinueWatchingHolder @Inject constructor() {
    private val _cwCategory = MutableStateFlow<Category?>(null)
    val cwCategory: StateFlow<Category?> = _cwCategory.asStateFlow()

    fun update(category: Category?) {
        _cwCategory.value = category
    }
}

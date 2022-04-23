package com.andreimikhailov.models

import org.http4k.template.ViewModel

data class EditEventModel(val prefix: String, val csrf: String, val prefill: EventDataToEdit): ViewModel

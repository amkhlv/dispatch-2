package com.andreimikhailov.models

import org.http4k.template.ViewModel
import java.time.LocalDateTime

data class DelEventModel (val prefix: String, val csrf: String, val id: Int, val description: String, val datetime: String): ViewModel

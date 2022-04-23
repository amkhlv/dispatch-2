package com.andreimikhailov.models

import org.http4k.template.ViewModel

data class MessageModel(val msg: String, val prefix: String): ViewModel

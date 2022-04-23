package com.andreimikhailov.models

import org.http4k.template.ViewModel

data class ChangePasswordModel (
    val prefix: String,
    val csrf: String
): ViewModel
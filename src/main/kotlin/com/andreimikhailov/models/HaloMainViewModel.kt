
package com.andreimikhailov.models

import org.http4k.template.ViewModel


data class HaloMainViewModel(
    val prefix: String,
    val csrf: String,
    val user: String?,
    val dayFrom: String,
    val dayUntil: String,
    val top: String
    ): ViewModel

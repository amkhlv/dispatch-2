
package com.andreimikhailov.models

import org.http4k.template.ViewModel


data class NewEventModel(val prefix: String, val csrf: String): ViewModel

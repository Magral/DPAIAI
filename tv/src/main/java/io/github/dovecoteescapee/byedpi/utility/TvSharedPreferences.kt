package io.github.dovecoteescapee.byedpi.utility

import android.content.SharedPreferences
fun SharedPreferences.getStringNotNull(key: String,defValue: String): String = getString(key,defValue) ?: defValue
